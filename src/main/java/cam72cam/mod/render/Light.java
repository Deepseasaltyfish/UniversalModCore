package cam72cam.mod.render;

import cam72cam.mod.event.ClientEvents;
import cam72cam.mod.event.CommonEvents;
import cam72cam.mod.math.Vec3d;
import cam72cam.mod.world.World;
import dev.lambdaurora.lambdynlights.api.DynamicLightHandlers;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ForgeConfigSpec;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;

public class Light {
    private static final ConcurrentLinkedDeque<LightEntity> lights = new ConcurrentLinkedDeque<>();

    private static final EntityType<LightEntity>[] types = new EntityType[16];

    private LightEntity internal;
    private double lightLevel;


    /**
     * return simulate Of dynamic light level
     * */
    public static double getSimulateOfDynamicLightLevel(Vec3d center) {
        if(!enabled()) return 0;
        double extra = 0.0;
        List<LightInfo> lights = getLightsInRange(center, 8.0);
        for (LightInfo light : lights) {
            double distSq = center.distanceToSquared(light.pos);
            if (distSq < 64.0) {
                double dist = Math.sqrt(distSq);
                double factor = 1.0 - dist / 8.0;
                extra = Math.max(light.level * factor, extra);
            }
        }
        return extra;
    }

    private static final Map<ChunkPos, List<LightEntity>> CHUNK_LIGHTS = new HashMap<>();
    private static final Object LOCK = new Object();
    private static void registerInChunk(LightEntity entity) {
        if (entity == null) return;
        ChunkPos cp = entity.chunkPosition();
        synchronized (LOCK) {
            CHUNK_LIGHTS.computeIfAbsent(cp, k -> new ArrayList<>()).add(entity);
        }
    }
    private static void unregisterFromChunk(LightEntity entity) {
        if (entity == null) return;
        ChunkPos cp = entity.chunkPosition();
        synchronized (LOCK) {
            List<LightEntity> list = CHUNK_LIGHTS.get(cp);
            if (list != null) {
                list.remove(entity);
                if (list.isEmpty()) CHUNK_LIGHTS.remove(cp);
            }
        }
    }
    public static List<LightInfo> getLightsInRange(Vec3d center, double radius) {
        int minX = (int)Math.floor((center.x - radius) / 16);
        int maxX = (int)Math.floor((center.x + radius) / 16);
        int minZ = (int)Math.floor((center.z - radius) / 16);
        int maxZ = (int)Math.floor((center.z + radius) / 16);
        List<LightInfo> result = new ArrayList<>();
        synchronized (LOCK) {
            for (int cx = minX; cx <= maxX; cx++) {
                for (int cz = minZ; cz <= maxZ; cz++) {
                    ChunkPos cp = new ChunkPos(cx, cz);
                    List<LightEntity> entities = CHUNK_LIGHTS.get(cp);
                    if (entities != null) {
                        for (LightEntity e : entities) {
                            if (!e.isAlive()) continue;//can avoid some ghost entities?
                            double dx = e.position().x - center.x;
                            double dy = e.position().y - center.y;
                            double dz = e.position().z - center.z;
                            if (dx*dx + dy*dy + dz*dz <= radius*radius) {
                                result.add(new LightInfo(new Vec3d(e.position().x, e.position().y, e.position().z), e.getSimulateLightLevel()));
                            }
                        }
                    }
                }
            }
        }
        return result;
    }
    public static class LightInfo {
        public final Vec3d pos;
        public final double level;
        public LightInfo(Vec3d pos, double level) {
            this.pos = pos;
            this.level = level;
        }
    }

    public Light(World world, Vec3d pos, double lightLevel) {
        init(world.internal, pos.internal(), lightLevel);
    }

    public void remove() {
        if(internal == null) return;
        unregisterFromChunk(internal);
        internal.remove(Entity.RemovalReason.KILLED);
        lights.remove(internal);
        internal = null;
    }

    public void setPosition(Vec3d pos) {
        if (internal == null) return;
        unregisterFromChunk(internal);
        internal.setPos(pos.x, pos.y, pos.z);
        registerInChunk(internal);
    }

    public void setLightLevel(double lightLevel) {
        init(internal.level(), internal.position(), lightLevel);
    }

    private void init(Level world, Vec3 pos, double lightLevel) {
        if (lightLevel == this.lightLevel) {
            // NOP
            return;
        }
        if (internal != null) {
            unregisterFromChunk(internal);
            internal.remove(Entity.RemovalReason.KILLED);
        }
        int ll = (int) Math.ceil((lightLevel * 15));
        ll = Math.min(ll, 15);
        ll = Math.max(ll, 1);
        EntityType<LightEntity> type = types[ll];
        internal = type.create(world);

        internal.setSimulateLightLevel(lightLevel);
        registerInChunk(internal);


        internal.setPos(pos.x, pos.y, pos.z);
//        world.addFreshEntity(internal);
        this.lightLevel = lightLevel;
        lights.add(internal);
    }

    public static void register() {
        CommonEvents.Entity.REGISTER.subscribe(helper -> {
            for (int i = 1; i <= 15; i++) {
                EntityType.Builder<LightEntity> builder = EntityType.Builder.of(LightEntity::new, MobCategory.MISC);
                builder.fireImmune();
                builder.sized(0, 0);

                EntityType<LightEntity> et = builder.build("light" + i);
                helper.register(ResourceLocation.parse("universalmodcore:light" + i), et);
                types[i] = et;
            }
        });
    }

    public static void registerClient() {
        if(isLDLInstalled()) {
            for (int i = 1; i <= 15; i++) {
                EntityType<LightEntity> et = types[i];
                int finalI = i;
                DynamicLightHandlers.registerDynamicLightHandler(et, e -> finalI);
            }
            ClientEvents.TICK.subscribe(Light::onClientTick);
        }
    }

    private static void onClientTick() {
        if(Minecraft.getInstance().isPaused()) return;
        for (LightEntity light : lights) {
            ClientLevel level = (ClientLevel) light.level();
            level.guardEntityTick(level::tickNonPassenger, light);
        }
    }

    // Client only
    private static class LightEntity extends Entity {
        private double simulateLightLevel;
        public LightEntity(EntityType<?> entityTypeIn, Level world) {
            super(entityTypeIn, world);
            super.noPhysics = true;
        }

        public void setSimulateLightLevel(double level) {
            this.simulateLightLevel = level;
        }

        public double getSimulateLightLevel() {
            return simulateLightLevel;
        }

        @Override
        public void remove(RemovalReason p_146834_) {
            unregisterFromChunk(this);
            super.remove(p_146834_);
        }

        @Override
        protected void defineSynchedData() {

        }

        @Override
        protected void readAdditionalSaveData(CompoundTag p_20052_) {

        }

        @Override
        protected void addAdditionalSaveData(CompoundTag p_20139_) {

        }

        @Override
        public Packet<ClientGamePacketListener> getAddEntityPacket() {
            return null;
        }
    }

    public static boolean enabled() {
        boolean flag = isLDLInstalled();
        try {
            //Some branch specific stuff
            //Need change once switch back to official LDL
            //i.e. SodiumDynamicLights.get().config.getEntitiesLightSource().get()
            Class<?> cls = Class.forName("toni.sodiumdynamiclights.SodiumDynamicLights");
            Method m1 = cls.getDeclaredMethod("get");
            Field f1 = cls.getDeclaredField("config");
            Class<?> config = Class.forName("toni.sodiumdynamiclights.DynamicLightsConfig");
            Object con = config.cast(f1.get(m1.invoke(null)));
            Method m2 = config.getDeclaredMethod("getEntitiesLightSource");
            return flag & ((ForgeConfigSpec.BooleanValue) m2.invoke(con)).get();
        } catch (ClassNotFoundException | NoSuchMethodException | NoSuchFieldException | InvocationTargetException |
                 IllegalAccessException e) {
            return flag;
        }
    }

    private static boolean isLDLInstalled() {
        try {
            Class<?> cls = Class.forName("dev.lambdaurora.lambdynlights.api.DynamicLightsInitializer");
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }
}
