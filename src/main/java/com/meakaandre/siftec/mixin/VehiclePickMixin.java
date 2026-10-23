package com.meakaandre.siftec.mixin;

import com.meakaandre.siftec.vehicle.VehicleEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Optional;
import java.util.function.Predicate;

/**
 * Picking a vehicle (the crosshair, using it, hitting it) goes by its turned body, not the square box round it: the box
 * the pick tests a vehicle with is shrunk to the point where the line meets the body (or to nothing far away if it
 * misses), so a Truck is picked all along its length and nowhere beside it.
 */
@Mixin(ProjectileUtil.class)
public abstract class VehiclePickMixin {
    /** Size of the box at the eye when the pick starts inside the body. */
    @Unique
    private static final double INSIDE_BOX = 0.02;
    /** Size of the tiny box the pick tests at the hit point (or far away on a miss). */
    @Unique
    private static final double POINT_BOX = 1.0e-3;
    /** How far below the eye a missed vehicle's box is put, out of any pick's reach. */
    @Unique
    private static final double MISS_DROP = 1.0e7;

    @Redirect(method = "getEntityHitResult(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;D)Lnet/minecraft/world/phys/EntityHitResult;",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getBoundingBox()Lnet/minecraft/world/phys/AABB;"))
    private static AABB siftec$vehicleBody(Entity entity, Entity shooter, Vec3 from, Vec3 to, AABB area, Predicate<Entity> filter, double distance) {
        if (!(entity instanceof VehicleEntity vehicle)) return entity.getBoundingBox();
        if (vehicle.bodyContains(from, 0)) return AABB.ofSize(from, INSIDE_BOX, INSIDE_BOX, INSIDE_BOX);
        Optional<Vec3> hit = vehicle.bodyHit(from, to, 0);
        if (hit.isEmpty()) return new AABB(from.x, from.y - MISS_DROP, from.z, from.x + POINT_BOX, from.y - MISS_DROP + POINT_BOX, from.z + POINT_BOX);
        Vec3 p = hit.get();
        return AABB.ofSize(p, POINT_BOX, POINT_BOX, POINT_BOX);
    }
}
