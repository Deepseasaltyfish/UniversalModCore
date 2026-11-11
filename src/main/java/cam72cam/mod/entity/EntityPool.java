package cam72cam.mod.entity;

import cam72cam.mod.ModCore;
import cam72cam.mod.math.Vec3d;
import cam72cam.mod.serialization.TagCompound;
import cam72cam.mod.world.World;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

import java.util.*;

/**
 * 离线实体池：让车辆类实体脱离 MC 区块 tick 独立运行。
 *
 * 核心思路（Create 式）：
 *   - Simulation 通过 CustomEntity 驱动逻辑，不依赖 ModdedEntity 是否在世界里
 *   - ModdedEntity 只是"临时躯壳"，进出世界时全部销毁重建，绕过 1.21 状态机残留
 *   - 每个 UUID 在池里始终关联一个 ModdedEntity（在线或孤儿），Simulation / 持久化都不用改
 */
public class EntityPool {
    private static final Map<World, EntityPool> INSTANCES = new HashMap<>();
    private static IEntityPoolPolicy policy = null;
    private static boolean registered = false;

    public static void register(IEntityPoolPolicy policyImpl) {
        policy = policyImpl;
        if (registered) return;
        registered = true;

        World.registerLifecycleListener(new World.EntityLifecycleListener() {
            @Override
            public void onEntityAdded(Entity entity) {
                if (!(entity instanceof ModdedEntity me)) return;
                World w = World.get(me.level());
                if (w == null || !w.isServer || policy == null || !policy.isEnabled()) return;

                EntityPool pool = get(w);
                UUID uuid = me.getUUID();

                ModdedEntity existing = pool.all.get(uuid);
                if (existing != null && existing != me) {
                    // 世界里来了一个 me（.mca 加载或 addFreshEntity 成功），
                    // 池里有 existing（孤儿）。让 me 接管，existing 丢弃。
                    final TagCompound mergedData;
                    try {
                        mergedData = existing.serializeToTagCompound();
                    } catch (Exception ex) {
                        ModCore.catching(ex, "Failed to serialize existing %s", uuid);
                        pool.all.remove(uuid);
                        me.setAddedToChunk(true);
                        pool.all.put(uuid, me);
                        return;
                    }
                    pool.all.remove(uuid);
                    me.setAddedToChunk(true);
                    pool.all.put(uuid, me);
                    try {
                        me.deserializeFromTagCompound(mergedData);
                    } catch (Exception ex) {
                        ModCore.catching(ex, "Failed to merge data into %s", uuid);
                    }
                    try {
                        policy.onEntitySpawned(me);
                    } catch (Exception ex) {
                        ModCore.catching(ex, "policy.onEntitySpawned failed for %s", uuid);
                    }
                    return;
                }

                me.setAddedToChunk(true);
                if (existing == null) {
                    pool.all.put(uuid, me);
                    try {
                        policy.onEntitySpawned(me);
                    } catch (Exception ex) {
                        ModCore.catching(ex, "policy.onEntitySpawned failed for %s", uuid);
                    }
                }
            }

            @Override
            public void onEntityRemoved(Entity entity) {
                if (!(entity instanceof ModdedEntity me)) return;
                World w = World.get(me.level());
                if (w == null || !w.isServer || policy == null || !policy.isEnabled()) return;

                EntityPool pool = get(w);
                UUID uuid = me.getUUID();
                if (pool.activelyDespawning.contains(uuid)) return;

                ModdedEntity current = pool.all.get(uuid);
                if (current != me) return;

                if (me.isRemoved()) {
                    pool.all.remove(uuid);
                } else {
                    me.setAddedToChunk(false);
                }
            }
        });

        World.onTick(EntityPool::onWorldTick);
    }

    public static CustomEntity lookupOfflineEntity(UUID uuid) {
        if (policy == null || !policy.isEnabled()) return null;
        for (EntityPool pool : INSTANCES.values()) {
            ModdedEntity e = pool.all.get(uuid);
            if (e != null) {
                CustomEntity self = e.getSelf();
                if (self != null) return self;
            }
        }
        return null;
    }

    public static EntityPool get(World world) {
        return INSTANCES.computeIfAbsent(world, EntityPool::new);
    }

    private final World world;
    private final Map<UUID, ModdedEntity> all = new HashMap<>();
    private final Set<UUID> activelyDespawning = new HashSet<>();
    private int tickCounter = 0;

    private EntityPool(World world) {
        this.world = world;
    }

    private static void onWorldTick(World w) {
        if (!w.isServer || policy == null) return;
        EntityPool pool = INSTANCES.get(w);
        if (pool == null) return;

        if (!policy.isEnabled()) {
            pool.flushAndDisable();
            return;
        }
        pool.tick();
    }

    private void tick() {
        // 只 tick 孤儿 ModdedEntity（不在世界里的），用 offlineTick 绕过 sync/乘客/dataManager
        for (ModdedEntity e : all.values()) {
            if (!e.isAddedToChunk() && !e.isRemoved()) {
                try {
                    e.offlineTick();
                } catch (Exception ex) {
                    ModCore.catching(ex, "Offline tick failed for %s", e.getUUID());
                }
            }
        }

        if (++tickCounter >= policy.getProximityInterval()) {
            tickCounter = 0;
            try {
                checkProximity();
            } catch (Exception ex) {
                ModCore.catching(ex, "checkProximity failed in world %s", world.getId());
            }
        }
    }

    private void checkProximity() {
        List<Player> players = world.getEntities(Player.class);
        if (players.isEmpty()) return;

        double spawnSq = policy.getSpawnDistanceSq();
        double despawnSq = policy.getDespawnDistanceSq();

        for (ModdedEntity e : all.values()) {
            if (e.isRemoved()) continue;
            CustomEntity self = e.getSelf();
            if (self != null && !self.getPassengers().isEmpty()) continue;

            double distSq = nearestPlayerDistSq(players, new Vec3d(e.position()));

            if (e.isAddedToChunk()) {
                if (distSq > despawnSq) despawn(e);
            } else {
                if (distSq < spawnSq) spawn(e);
            }
        }
    }

    private double nearestPlayerDistSq(List<Player> players, Vec3d pos) {
        double best = Double.MAX_VALUE;
        for (Player p : players) {
            double d = p.internal.position().distanceToSqr(pos.internal());
            if (d < best) best = d;
        }
        return best;
    }

    /**
     * 车离开视野：销毁当前 ModdedEntity，用它的完整 NBT 重建一个孤儿 ModdedEntity，
     * 让 CustomEntity.internal 指向新孤儿。Simulation 通过 CustomEntity 继续驱动。
     *
     * 关键：新孤儿从未进入过世界，1.21 状态机对它无任何记录。
     */
    private void despawn(ModdedEntity real) {
        if (!real.isAddedToChunk()) return;
        if (!(real.level() instanceof ServerLevel sl)) return;

        UUID uuid = real.getUUID();
        activelyDespawning.add(uuid);
        try {
            if (real.getPassengerCount() > 0) {
                ModCore.warn("[POOL] despawn %s: still has passengers, skipping", uuid);
                return;
            }

            CustomEntity self = real.getSelf();
            if (self == null) {
                ModCore.warn("[POOL] despawn %s: self is null", uuid);
                real.setAddedToChunk(false);
                return;
            }

            TagCompound data;
            try {
                data = real.serializeToTagCompound();
            } catch (Exception ex) {
                ModCore.catching(ex, "despawn %s: serialize failed", uuid);
                real.setAddedToChunk(false);
                return;
            }

            // 从区块移除（会触发 onTrackingEnd、清 sectionStorage）
            sl.getChunkSource().removeEntity(real);

            // 强制清理 UUID 注册层。Set.remove 是幂等的，即使不在也不会出问题。
            boolean knownRemoved = sl.entityManager.knownUuids.remove(uuid);

            // 兜底清理 EntityLookup（幂等，EntityLookup.remove 按 UUID + ID 双删除）
            sl.entityManager.visibleEntityStorage.remove(real);

            if (!knownRemoved) {
                // removeEntity 已经清过了，正常路径
                ModCore.info("[POOL] despawn %s: knownUuids already clean", uuid);
            }

            real.discard();

            EntityType type = real.getType();
            ModdedEntity orphan = new ModdedEntity(type, real.level(), self);
            orphan.deserializeFromTagCompound(data);
            orphan.setAddedToChunk(false);

            all.put(uuid, orphan);
            spawnFailures.remove(uuid);
        } catch (Exception ex) {
            ModCore.catching(ex, "despawn failed for %s", uuid);
        } finally {
            activelyDespawning.remove(uuid);
        }
    }

    /**
     * 车进入视野：销毁孤儿 ModdedEntity，用它的完整 NBT 重建一个全新的真实 ModdedEntity，
     * 然后 addFreshEntity。新对象从未被 1.21 状态机见过，必然走完整首次注册。
     */
    private final Map<UUID, Integer> spawnFailures = new HashMap<>();
    private static final int MAX_SPAWN_FAILURES = 10;

    private void spawn(ModdedEntity orphan) {
        if (orphan.isAddedToChunk()) return;
        if (!(orphan.level() instanceof ServerLevel sl)) return;

        UUID uuid = orphan.getUUID();

        int failures = spawnFailures.getOrDefault(uuid, 0);
        if (failures >= MAX_SPAWN_FAILURES) {
            return;
        }

        CustomEntity self = orphan.getSelf();
        if (self == null) {
            ModCore.warn("[POOL] spawn %s: self is null", uuid);
            return;
        }

        ensureChunkLoaded(orphan);

        // 关键：如果世界里已有同 UUID 实体（.mca 加载的 / 或被 onEntityAdded 接管的），
        // 走合并路径，不要创建新的 real 去 addFreshEntity（会造成 UUID 冲突）
        Entity worldEntity = sl.getEntity(uuid);
        if (worldEntity instanceof ModdedEntity worldMe) {
            TagCompound mergeData;
            try {
                mergeData = orphan.serializeToTagCompound();
            } catch (Exception ex) {
                ModCore.catching(ex, "spawn %s: serialize for merge failed", uuid);
                return;
            }
            try {
                worldMe.deserializeFromTagCompound(mergeData);
            } catch (Exception ex) {
                ModCore.catching(ex, "spawn %s: merge into world entity failed", uuid);
            }
            worldMe.setAddedToChunk(true);
            all.put(uuid, worldMe);
            orphan.discard();
            spawnFailures.remove(uuid);
            return;
        }

        TagCompound data;
        try {
            data = orphan.serializeToTagCompound();
        } catch (Exception ex) {
            ModCore.catching(ex, "spawn %s: serialize failed", uuid);
            return;
        }

        EntityType type = orphan.getType();
        ModdedEntity real = new ModdedEntity(type, orphan.level(), self);
        real.deserializeFromTagCompound(data);

        all.put(uuid, real);
        orphan.discard();

        if (sl.addFreshEntity(real)) {
            spawnFailures.remove(uuid);
            return;
        }

        // 第一次失败：knownUuids 里可能有幽灵 UUID（despawn 时 removeEntity 后
        // PersistentEntitySectionManager 的延迟清理尚未完成，或某种竞态重新写回）。
        // 场景：spawn 被 checkProximity 立即触发，despawn 的清理还没走完流程。
        // 强制清一次 knownUuids + visibleEntityStorage，然后重试一次。
        // 副作用：此时世界里确实没有同 UUID 实体（上面 getEntity 已确认），清掉是安全的。
        sl.entityManager.knownUuids.remove(uuid);
        sl.entityManager.visibleEntityStorage.remove(real);

        if (sl.addFreshEntity(real)) {
            spawnFailures.remove(uuid);
            ModCore.info("[POOL] addFreshEntity succeeded after purge for %s", uuid);
            return;
        }

        // 重试仍失败：还有其他原因，计入失败计数
        spawnFailures.put(uuid, failures + 1);
        boolean stillKnown = sl.entityManager.knownUuids.contains(uuid);
        ModCore.warn("[POOL] addFreshEntity failed for %s (attempt %d/%d) after purge. knownUuids.contains=%s",
                uuid, failures + 1, MAX_SPAWN_FAILURES, stillKnown);
    }

    private void ensureChunkLoaded(ModdedEntity e) {
        int chunkX = e.chunkPosition().x;
        int chunkZ = e.chunkPosition().z;
        e.level().getChunk(chunkX, chunkZ);
    }

    private void flushAndDisable() {
        if (all.isEmpty()) return;
        for (ModdedEntity e : new ArrayList<>(all.values())) {
            if (!e.isAddedToChunk() && !e.isRemoved()) {
                try {
                    spawn(e);
                } catch (Exception ex) {
                    ModCore.catching(ex, "flushAndDisable failed for %s", e.getUUID());
                }
            }
        }
        all.clear();
        activelyDespawning.clear();
    }

    public Collection<ModdedEntity> getAllEntities() {
        return all.values();
    }

    public ModdedEntity getEntity(UUID uuid) {
        return all.get(uuid);
    }

    /**
     * 从持久化数据恢复实体。
     * 如果世界已有同 UUID 的实体（.mca 加载），合并数据进它。
     * 否则创建一个孤儿 ModdedEntity（不 addFreshEntity），由 pool.tick 驱动。
     */
    public ModdedEntity createFromTagCompound(TagCompound data) {
        UUID uuid = data.internal.contains("UUID") ? data.internal.getUUID("UUID") : null;
        if (uuid != null && world.internal instanceof ServerLevel sl) {
            Entity worldExisting = sl.getEntity(uuid);
            if (worldExisting instanceof ModdedEntity me) {
                try {
                    me.deserializeFromTagCompound(data);
                } catch (Exception ex) {
                    ModCore.catching(ex, "Failed to merge file data into world entity %s", uuid);
                }
                me.setAddedToChunk(true);
                if (policy != null && policy.shouldPool(me.getSelf())) {
                    all.put(uuid, me);
                }
                return me;
            }
        }

        ModdedEntity e = world.createModdedEntity(data);
        e.deserializeFromTagCompound(data);
        e.setAddedToChunk(false);
        if (policy != null && policy.shouldPool(e.getSelf())) {
            all.put(e.getUUID(), e);
        }
        return e;
    }
}