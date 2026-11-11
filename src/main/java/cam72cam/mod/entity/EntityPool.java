package cam72cam.mod.entity;

import cam72cam.mod.ModCore;
import cam72cam.mod.math.Vec3d;
import cam72cam.mod.serialization.TagCompound;
import cam72cam.mod.world.World;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Entity.RemovalReason;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;

import java.util.*;

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

                if (me.isRemoved() && me.getRemovalReason() != RemovalReason.UNLOADED_TO_CHUNK) {
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
        for (ModdedEntity e : all.values()) {
            if (!e.isAddedToChunk() && !e.isRemoved()) {
                try {
                    e.tick();
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
     * 将实体从 MC 世界移除，但保留在池中（转离线状态）。
     * 不能走 e.remove()：它会把 isRemoved 置 true，pool.tick 和 checkProximity 都会跳过，
     * Simulation 不再驱动该车，地图也看不到。
     * 正确做法：清三层注册表（ChunkMap 追踪 / EntityLookup 实体存储 / knownUuids UUID 登记），
     * 但保持 isRemoved = false，让实体继续被池子 tick。
     */
    private void despawn(ModdedEntity e) {
        if (!e.isAddedToChunk()) return;
        if (!(e.level() instanceof ServerLevel sl)) return;

        UUID uuid = e.getUUID();
        activelyDespawning.add(uuid);
        try {
            // 1. 清 ChunkMap 追踪层（客户端收到 remove 包）
            sl.getChunkSource().removeEntity(e);
            // 2. 清 EntityLookup.byUuid/byId（下次 addFreshEntity 不会 Duplicate entity UUID）
            sl.entityManager.visibleEntityStorage.remove(e);
            // 3. 清 knownUuids
            sl.entityManager.knownUuids.remove(uuid);
            // 4. 标记离线，isRemoved 保持 false，让池子继续 tick
            e.setAddedToChunk(false);
        } finally {
            activelyDespawning.remove(uuid);
        }
    }

    private void spawn(ModdedEntity e) {
        if (e.isAddedToChunk()) return;
        if (!(e.level() instanceof ServerLevel sl)) return;

        ensureChunkLoaded(e);

        // 世界中已有同 UUID 实体（.mca 加载的），合并数据进它，不重复添加
        Entity worldEntity = sl.getEntity(e.getUUID());
        if (worldEntity instanceof ModdedEntity existing) {
            try {
                TagCompound data = e.serializeToTagCompound();
                existing.deserializeFromTagCompound(data);
            } catch (Exception ex) {
                ModCore.catching(ex, "Failed to merge data into world entity %s", e.getUUID());
            }
            existing.setAddedToChunk(true);
            all.put(e.getUUID(), existing);
            return;
        }

        // 世界中没有，正常添加
        e.revive();
        removeFromKnownUuids(sl, e.getUUID());
        removeFromChunkMap(sl, e.getId());

        if (!sl.addFreshEntity(e)) {
            PersistentEntitySectionManager<Entity> manager = sl.entityManager;
            boolean stillKnown = manager.knownUuids.contains(e.getUUID());
            ModCore.warn("[POOL] addFreshEntity failed for %s. knownUuids.contains=%s", e.getUUID(), stillKnown);
        }
    }

    private void ensureChunkLoaded(ModdedEntity e) {
        int chunkX = e.chunkPosition().x;
        int chunkZ = e.chunkPosition().z;
        e.level().getChunk(chunkX, chunkZ);
    }

    private static void removeFromKnownUuids(ServerLevel level, UUID uuid) {
        try {
            PersistentEntitySectionManager<Entity> manager = level.entityManager;
            if (manager != null && manager.knownUuids != null) {
                manager.knownUuids.remove(uuid);
            }
        } catch (Exception ex) {
            ModCore.warn("[POOL] Failed to remove uuid from knownUuids: %s", ex.getMessage());
        }
    }

    private static void removeFromChunkMap(ServerLevel level, int entityId) {
        try {
            ChunkMap chunkMap = level.getChunkSource().chunkMap;
            Int2ObjectMap<Object> map = (Int2ObjectMap<Object>) (Int2ObjectMap<?>) chunkMap.entityMap;
            if (map != null) {
                map.remove(entityId);
            }
        } catch (Exception ex) {
            ModCore.warn("[POOL] Failed to remove entity id %d from ChunkMap.entityMap: %s", entityId, ex.getMessage());
        }
    }

    private void flushAndDisable() {
        if (all.isEmpty()) return;
        for (ModdedEntity e : new ArrayList<>(all.values())) {
            if (!e.isAddedToChunk() && !e.isRemoved()) {
                ensureChunkLoaded(e);
                if (e.level() instanceof ServerLevel sl) {
                    e.revive();
                    removeFromKnownUuids(sl, e.getUUID());
                    removeFromChunkMap(sl, e.getId());
                    if (sl.addFreshEntity(e)) {
                        e.setAddedToChunk(true);
                    }
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