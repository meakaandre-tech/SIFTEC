package com.meakaandre.siftec.drone;

import com.meakaandre.siftec.Siftec;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * The Cardboard Drone as you see it: moved by its Drone Port each tick (the trip itself is worked out by time on the
 * port, so it goes on where nothing is loaded and this entity is only shown where it can be). It is never saved, so a
 * drone left over from a crash never lingers. Drawn by {@code client.DroneRenderer} from Create's own textures: a
 * cardboard body on a brass frame with four spinning propellers, tilting as it flies.
 */
public class DroneEntity extends Entity {
    private static final EntityDataAccessor<Boolean> FLYING = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> LOADED = SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.BOOLEAN);
    public static EntityType<DroneEntity> TYPE;

    // ---- client only: how it leans, eased from how it moves
    public float pitch, pitchO, roll, rollO, spin, spinO;
    private double lastX = Double.NaN, lastY, lastZ;

    public DroneEntity(EntityType<? extends DroneEntity> type, Level level) {
        super(type, level);
        noPhysics = true;
        setNoGravity(true);
    }

    public static void register() {
        ResourceKey<EntityType<?>> key = ResourceKey.create(Registries.ENTITY_TYPE, Siftec.id("cardboard_drone"));
        TYPE = Registry.register(BuiltInRegistries.ENTITY_TYPE, key,
            EntityType.Builder.<DroneEntity>of(DroneEntity::new, MobCategory.MISC).noLootTable().fireImmune().sized(1.3f, 0.6f)
                .clientTrackingRange(10).updateInterval(1).build(key));
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(FLYING, false);
        builder.define(LOADED, false);
    }

    /** Whether its propellers turn (on its way, taking off and landing), and whether it carries packages. */
    public void set(boolean flying, boolean loaded) {
        if (entityData.get(FLYING) != flying) entityData.set(FLYING, flying);
        if (entityData.get(LOADED) != loaded) entityData.set(LOADED, loaded);
    }

    public boolean flying() {
        return entityData.get(FLYING);
    }

    public boolean loaded() {
        return entityData.get(LOADED);
    }

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide()) return;
        pitchO = pitch;
        rollO = roll;
        spinO = spin;
        if (flying()) spin += 50f;
        if (!Double.isNaN(lastX)) {
            // it leans into where it is going: nose down as it speeds up along its heading, level while it climbs or hovers
            double dx = getX() - lastX, dz = getZ() - lastZ;
            float yaw = getYRot() * net.minecraft.util.Mth.DEG_TO_RAD;
            double ahead = -net.minecraft.util.Mth.sin(yaw) * dx + net.minecraft.util.Mth.cos(yaw) * dz;
            double side = net.minecraft.util.Mth.cos(yaw) * dx + net.minecraft.util.Mth.sin(yaw) * dz;
            float wantPitch = (float) net.minecraft.util.Mth.clamp(ahead * 30, -25, 25), wantRoll = (float) net.minecraft.util.Mth.clamp(side * 30, -20, 20);
            pitch += (wantPitch - pitch) * 0.15f;
            roll += (wantRoll - roll) * 0.15f;
        }
        lastX = getX();
        lastY = getY();
        lastZ = getZ();
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        return false;
    }

    @Override
    protected void readAdditionalSaveData(ValueInput view) {
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput view) {
    }
}
