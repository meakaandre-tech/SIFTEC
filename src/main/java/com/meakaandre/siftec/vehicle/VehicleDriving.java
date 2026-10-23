package com.meakaandre.siftec.vehicle;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** A vehicle's driving physics: throttle, steering and brake become speed and movement, its ends kept out of walls. */
final class VehicleDriving {
    // ---- driving: speeds are blocks a tick, shares are of the kind's top speed
    /** Speed kept each tick while braking, and while rolling with no throttle. */
    private static final double BRAKE_DRAG = 0.7, COAST_DRAG = 0.94;
    /** Top speed in reverse, as a share of the top speed forward. */
    private static final double REVERSE_SHARE = 0.4;
    /** Below this speed it stands still. */
    private static final double STOP_SPEED = 0.003;
    /** The share of top speed at which its wheels turn it fully; slower, it turns less. */
    static final double FULL_GRIP_SHARE = 0.3;
    /** Downward speed while on the ground; gravity and air drag a tick while falling (vanilla's). */
    private static final double GROUND_VY = -0.04, GRAVITY = 0.08, AIR_DRAG = 0.98;
    /** In water: lift a tick, the fastest it rises, and the speed it keeps a tick. */
    private static final double SWIM_LIFT = 0.06, MAX_SWIM_RISE = 0.1, WATER_DRAG = 0.9;
    /** Least change of speed that is sent to clients. */
    private static final double SPEED_SYNC_STEP = 0.005;
    /**
     * {@link #bodyBlocked}: the share of its width checked, how high over its feet the check starts (over a one-block
     * step) and ends at least, and the gap left under its roof.
     */
    private static final double BODY_CHECK_WIDTH = 0.7, STEP_CLEARANCE = 1.05, MIN_CHECK_TOP = 1.1, ROOF_GAP = 0.05;

    private VehicleDriving() {
    }

    /** Throttle and steering (-1 to 1 each, steering right positive) and the brake: turns, speeds up or slows, and moves. */
    static void drive(VehicleEntity vehicle, double throttle, double steer, boolean brake) {
        double max = vehicle.kind.maxSpeed;
        if (brake) {
            vehicle.speed *= BRAKE_DRAG;
        } else if (throttle != 0) {
            vehicle.speed = Mth.clamp(vehicle.speed + throttle * vehicle.kind.accel, -max * REVERSE_SHARE, max);
        } else {
            vehicle.speed *= COAST_DRAG;
        }
        if (Math.abs(vehicle.speed) < STOP_SPEED) vehicle.speed = 0;
        Vec3 before = vehicle.position();
        // what its body already stands in (a dock it parked in, say) does not hold it; only driving into more does
        int blockedBefore = vehicle.speed == 0 ? 0 : bodyBlocked(vehicle);
        if (steer != 0 && vehicle.speed != 0) {
            double grip = Math.min(1, Math.abs(vehicle.speed) / (max * FULL_GRIP_SHARE));
            vehicle.setYRot(vehicle.getYRot()
                + (float) (Mth.clamp(steer, -1, 1) * vehicle.kind.turnDegrees * grip * Math.signum(vehicle.speed)));
        }
        Vec3 f = vehicle.forward();
        Vec3 v = vehicle.getDeltaMovement();
        double vy = vehicle.onGround() ? GROUND_VY : (v.y - GRAVITY) * AIR_DRAG;
        if (vehicle.isInWater()) {
            vy = Math.min(v.y + SWIM_LIFT, MAX_SWIM_RISE);
            vehicle.speed *= WATER_DRAG;
        }
        vehicle.setDeltaMovement(f.x * vehicle.speed, vy, f.z * vehicle.speed);
        // it moves as the square middle of its body; its front and back must not end up in a wall
        vehicle.moving = true;
        vehicle.setBoundingBox(vehicle.makeBoundingBox(vehicle.position()));
        vehicle.move(MoverType.SELF, vehicle.getDeltaMovement());
        vehicle.moving = false;
        // the end it drives toward stops at a wall as its middle does (turning is never held back: a tail swinging
        // past a corner does not stop it)
        int lead = vehicle.speed > 0 ? 1 << (squares(vehicle) - 1) : 1;
        if (vehicle.speed != 0 && (bodyBlocked(vehicle) & ~blockedBefore & lead) != 0) {
            vehicle.setPos(before.x, vehicle.getY(), before.z);
            vehicle.speed *= 0.5;
            vehicle.horizontalCollision = true;
        }
        vehicle.setBoundingBox(vehicle.makeBoundingBox(vehicle.position()));
        if (vehicle.horizontalCollision) vehicle.speed *= 0.5;
        if (Math.abs(vehicle.getEntityData().get(VehicleEntity.SPEED) - vehicle.speed) > SPEED_SYNC_STEP)
            vehicle.getEntityData().set(VehicleEntity.SPEED, (float) vehicle.speed);
    }

    /**
     * Which squares along its length (front to back) have something solid in them above the height it can drive up (one
     * block), as bits: its front and back stop at a wall as its middle does. The squares reach its very ends.
     */
    static int bodyBlocked(VehicleEntity vehicle) {
        double w = vehicle.kind.boxWidth, l = vehicle.kind.boxLength;
        int n = squares(vehicle);
        double side = w * BODY_CHECK_WIDTH / 2, from = vehicle.getY() + STEP_CLEARANCE,
            to = vehicle.getY() + Math.max(MIN_CHECK_TOP, vehicle.kind.boxHeight - ROOF_GAP);
        double reach = Math.max(0, l / 2 - side);
        int mask = 0;
        for (int i = 0; i < n; i++) {
            double z = n == 1 ? 0 : -reach + i * (2 * reach) / (n - 1);
            Vec3 c = vehicle.local(0, 0, z);
            if (!vehicle.level().noCollision(vehicle, new AABB(c.x - side, from, c.z - side, c.x + side, to, c.z + side)))
                mask |= 1 << i;
        }
        return mask;
    }

    /** How many squares {@link #bodyBlocked} checks along its length (the first is its back, the last its front). */
    static int squares(VehicleEntity vehicle) {
        return Math.max(1, (int) Math.ceil(vehicle.kind.boxLength / vehicle.kind.boxWidth));
    }
}
