package cam72cam.mod.render;

import cam72cam.mod.ModCore;
import cam72cam.mod.event.CommonEvents;
import cam72cam.mod.math.Vec3d;
import cam72cam.mod.world.World;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.InvocationTargetException;
import java.util.Objects;

public class Light {
    private static EntityType<LightEntity>[] types = new EntityType[16];

    private LightEntity internal;
    private double lightLevel;

    //LDL compat
    private static boolean ldlAvailable = false;
    private static boolean ldlChecked = false;
    private static boolean ldlRegistered = false;
    private static boolean isLDLPresent() {
        if (!ldlChecked) {
            try {
                Class.forName("dev.lambdaurora.lambdynlights.api.DynamicLightHandlers");
                ldlAvailable = true;
            } catch (ClassNotFoundException e) {
                ldlAvailable = false;
            }
            ldlChecked = true;
        }
        return ldlAvailable;
    }

    private static void doRegisterLightEntityType(EntityType<LightEntity> type) {
        if (!isLDLPresent()) return;
        try {
            Class<?> handlerClass = Class.forName("dev.lambdaurora.lambdynlights.api.DynamicLightHandler");

            Object handler = java.lang.reflect.Proxy.newProxyInstance(
                    handlerClass.getClassLoader(),
                    new Class<?>[]{handlerClass},
                    (proxy, method, args) -> {
                        String methodName = method.getName();

                        if ("getLuminance".equals(methodName) && args.length == 1) {
                            if (args[0] instanceof LightEntity) {
                                return (int) Math.round(((LightEntity) args[0]).getLightLevel());
                            }
                            return 0;
                        }

                        if ("isWaterSensitive".equals(methodName) && args.length == 1) {
                            return false;
                        }

                        return null;
                    });

            Class<?> handlersClass = Class.forName("dev.lambdaurora.lambdynlights.api.DynamicLightHandlers");
            handlersClass.getMethod("registerDynamicLightHandler", EntityType.class, handlerClass)
                    .invoke(null, type, handler);

            ldlRegistered = true;
            ModCore.debug("Registered dynamic light handler for " + type);
        } catch (Exception ex) {
            ModCore.debug("Failed to register dynamic light handler");
            ex.printStackTrace();
        }
    }

    public Light(World world, Vec3d pos, double lightLevel) {
        Level level = world.internal;
        if (!level.isClientSide() && Minecraft.getInstance().level != null) {
            ModCore.debug("not client");
            level = Minecraft.getInstance().level;
        }else {
            ModCore.debug("client");
        }
        init(level, pos.internal(), lightLevel);
    }

    public void remove() {
        if (internal != null) {
            ModCore.debug("remove");
            internal.remove(Entity.RemovalReason.KILLED);
            internal = null;
        }
    }

    public void setPosition(Vec3d pos) {
        if (internal != null) {
            ModCore.debug("setpos:" + pos);
            internal.setPos(pos.x, pos.y, pos.z);
        }
    }

    public void setLightLevel(double lightLevel) {
        if (internal != null) {
            ModCore.debug("setlight:" + lightLevel);
            init(internal.level(), internal.position(), lightLevel);
        }
    }

    private void init(Level world, Vec3 pos, double lightLevel) {
        ModCore.debug("init: pos" + pos + " level:" + lightLevel);
        if (lightLevel == this.lightLevel) {
            // NOP
            return;
        }
        if (internal != null) {
            internal.remove(Entity.RemovalReason.KILLED);
        }
        int ll = (int) Math.ceil((lightLevel * 15));
        ll = Math.min(ll, 15);
        ll = Math.max(ll, 1);
        EntityType<LightEntity> type = types[ll];
        internal = type.create(world);
        internal.setPos(pos.x, pos.y, pos.z);
        world.addFreshEntity(internal);
        internal.setLightLevel(lightLevel * 15);
        this.lightLevel = lightLevel;
        ModCore.debug("init finished");
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
            if (isLDLPresent()) {
                for (int i = 1; i <= 15; i++) {
                    if (types[i] != null) {
                        doRegisterLightEntityType(types[i]);
                    }
                }
            }
        });
    }

    // Client only
    private static class LightEntity extends Entity {
        private double lightLevel = 0;
        public LightEntity(EntityType<?> entityTypeIn, Level world) {
            super(entityTypeIn, world);
            super.noPhysics = false;
            this.setBoundingBox(new AABB(0, 0, 0, 0.5, 0.5, 0.5));
            this.setInvulnerable(true);
        }

        @Override
        public void tick() {
            super.tick();
            // 打印位置和存活状态，确认每帧都在
            if (this.level().isClientSide && this.tickCount % 20 == 0) {
                ModCore.debug("LightEntity tick at c" + this.position());
            }

            if (!this.level().isClientSide && this.tickCount % 20 == 0) {
                ModCore.debug("LightEntity tick at s" + this.position());
            }
        }

        @Override
        public void onAddedToWorld() {
            super.onAddedToWorld();
            ModCore.debug("LightEntity added to world, chunk loaded: " + this.level().hasChunkAt(this.blockPosition()));
        }

        @Override
        public void onRemovedFromWorld() {
            super.onRemovedFromWorld();
            ModCore.debug("LightEntity removed from world");
        }

        @Override
        public boolean isAlwaysTicking() {
            return true;
        }

        @Override
        public void remove(RemovalReason reason) {
            ModCore.debug("LightEntity removed: " + reason);
            super.remove(reason);
        }

        private void setLightLevel(double level) {
            this.lightLevel = level;
        }

        private double getLightLevel() {
            return lightLevel;
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
        if (isLDLPresent()) {
            return true;
        }
        if (!OptiFine.isLoaded()) {
            return false;
        }
        try {
            Class<?> optiConfig = Class.forName("Config");
            return Objects.equals(true, optiConfig.getDeclaredMethod("isDynamicLights").invoke(null));
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            return false;
        }
    }
}