package cam72cam.mod.entity;

import cam72cam.mod.ModCore;
import cam72cam.mod.math.Vec3d;
import cam72cam.mod.math.Vec3i;
import cam72cam.mod.serialization.TagCompound;
import cam72cam.mod.world.World;

import java.util.*;
import java.util.stream.Collectors;

public class EntityPool {
    private static final Map<World, EntityPool> INSTANCES = new HashMap<>();
    private static IEntityPoolPolicy policy = null;
    private static boolean registered = false;

    // 注册策略（由 IR 启动时调用）
    public static void register(IEntityPoolPolicy policyImpl) {
        policy = policyImpl;
        if (!registered) {
            registered = true;
            World.registerLifecycleListener(new World.EntityLifecycleListener() {
                @Override
                public void onEntityAdded(net.minecraft.entity.Entity entity) {
                    if (!(entity instanceof ModdedEntity)) return;
                    World w = World.get(entity.world);
                    if (w == null || !w.isServer || policy == null || !policy.isEnabled()) return;

                    UUID uuid = entity.getPersistentID();
                    EntityPool pool = get(w);
                    ModdedEntity current = pool.all.get(uuid);

                    if (current == entity) return;
                    if (current != null) {
                        // 出现重复的野实体，干掉他
                        entity.setDead();
                        return;
                    }
                    pool.add((ModdedEntity) entity);
                }

                @Override
                public void onEntityRemoved(net.minecraft.entity.Entity entity) {
                    if (!(entity instanceof ModdedEntity)) return;
                    World w = World.get(entity.world);
                    if (w == null || !w.isServer || policy == null || !policy.isEnabled()) return;

                    EntityPool pool = get(w);
                    UUID uuid = entity.getPersistentID();
                    if (pool.activelyDespawning.contains(uuid)) return;

                    ModdedEntity current = pool.all.get(uuid);
                    if (current != null && current != entity) return;

                    if (entity.isDead) {
                        pool.remove(uuid);
                    }
                }
            });

            World.onTick(EntityPool::onWorldTick);
        }
    }

    // 提供统一的查实体的接口，直接干掉之前 UMC World 里的 offlineEntityLookup
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

    private void add(ModdedEntity e) {
        if (policy == null || !policy.isEnabled()) return;
        if (policy.shouldPool(e.getSelf())) {
            UUID uuid = e.getPersistentID();
            ModdedEntity existing = all.put(uuid, e);

            // 【新增】：如果这是一个重新回到世界的实体，触发策略回调
            if (existing != e && e.isAddedToChunk()) {
                policy.onEntitySpawned(e);
            }
        }
    }

    private void remove(UUID uuid) {
        all.remove(uuid);
    }

    private void tick() {
        // 1. 手动 tick 离线实体
        for (ModdedEntity e : all.values()) {
            if (!e.isAddedToChunk() && !e.isDead) {
                try {
                    e.onUpdate();
                } catch (Exception ex) {
                    ModCore.catching(ex, "Offline tick failed for %s", e.getPersistentID());
                }
            }
        }

        // 2. 定期扫描距离
        if (++tickCounter >= policy.getProximityInterval()) {
            tickCounter = 0;
            try {
                checkProximity();
            } catch (Exception ex) {
                ModCore.catching(ex, "checkProximity failed in world %d", world.getId());
            }
        }
    }

    private void checkProximity() {
        List<Player> players = world.getEntities(Player.class);
        if (players.isEmpty()) return;

        for (ModdedEntity e : all.values()) {
            if (e.isDead) continue;

            CustomEntity self = e.getSelf();
            if (self != null && !self.getPassengers().isEmpty()) continue;

            double distSq = nearestPlayerDistSq(players, new Vec3d(e.getPositionVector()));

            if (e.isAddedToChunk()) {
                if (distSq > policy.getDespawnDistanceSq()) despawn(e);
            } else {
                if (distSq < policy.getSpawnDistanceSq()) spawn(e);
            }
        }
    }

    private double nearestPlayerDistSq(List<Player> players, Vec3d pos) {
        double best = Double.MAX_VALUE;
        for (Player p : players) {
            double d = p.internal.getPositionVector().squareDistanceTo(pos.internal());
            if (d < best) best = d;
        }
        return best;
    }

    private void despawn(ModdedEntity e) {
        UUID uuid = e.getPersistentID();
        activelyDespawning.add(uuid);
        try {
            world.internal.removeEntityDangerously(e);
            if (e.isAddedToChunk()) {
                e.setAddedToChunk(false);
                world.internal.loadedEntityList.remove(e);
            }
            e.isDead = false;
        } finally {
            activelyDespawning.remove(uuid);
        }
    }

    // 提取一个公共方法
    private void ensureChunkLoaded(ModdedEntity e, boolean longTermProtect) {
        int chunkX = ((int) Math.floor(e.posX)) >> 4;
        int chunkZ = ((int) Math.floor(e.posZ)) >> 4;

        // 1. 确保区块在内存里
        e.world.getChunk(chunkX, chunkZ);

        // 2. 如果是在关池子时的批量强退，必须给它加个锁，防止秒卸载
        if (longTermProtect) {
            world.keepLoaded(new Vec3i(chunkX << 4, 0, chunkZ << 4));
        }
    }

    public void spawn(ModdedEntity e) {
        e.isDead = false;
        if (!e.isAddedToChunk()) {
            // 正常上线：需要加载，但不需要强行锁
            ensureChunkLoaded(e, false);
            world.internal.spawnEntity(e);
            world.registerEntity(e.getSelf());
        }
    }

    private void flushAndDisable() {
        if (all.isEmpty()) return;
        for (ModdedEntity e : new ArrayList<>(all.values())) {
            e.isDead = false;
            if (!e.isAddedToChunk()) {
                // 关池子强退：必须加载 + 锁住几秒
                ensureChunkLoaded(e, true);
                world.internal.spawnEntity(e);
                world.registerEntity(e.getSelf());

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
        ModdedEntity e = world.createModdedEntity();
        e.deserializeFromTagCompound(data);
        e.setAddedToChunk(false);
        e.isDead = false;
        add(e);
        return e;
    }
}