package com.meakaandre.siftec.vehicle;

import com.meakaandre.siftec.building.TruckStations;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** A vehicle driving its recorded route by itself: the keys it presses, its stops at the docks, and its leg times. */
final class VehicleAutopilot {
    /** Farther than this from its next point, it carries on from the nearest point instead. */
    private static final int OFF_ROUTE_DISTANCE = 24;
    /** How close it must come to a stop, and to any other point, to have reached it. */
    private static final double STOP_ARRIVE = 1.2, POINT_ARRIVE = 1.8;
    /** Ticks without getting closer before it gives up on a point. */
    private static final int STUCK_TICKS = 80;
    /** Degrees off its heading beyond which it slows for the turn, and how close to a stop it slows to dock. */
    private static final int SHARP_TURN_DEGREES = 45, APPROACH_DISTANCE = 6;
    /** Autopilot speeds: turning hard, coming up to a stop, cruising; and how far over its limit it goes before braking. */
    private static final double TURN_SPEED_SHARE = 0.3, APPROACH_SPEED_SHARE = 0.35, CRUISE_SPEED_SHARE = 0.8;
    private static final double BRAKE_MARGIN = 0.05;
    /** The least grip the autopilot reckons with, so its steering never divides by zero. */
    private static final double MIN_GRIP = 0.05;

    private VehicleAutopilot() {
    }

    private static int stopIndex(VehicleEntity vehicle, int point) {
        for (int i = 0; i < vehicle.stopAt.size(); i++) if (vehicle.stopAt.get(i) == point) return i;
        return -1;
    }

    /** Driving the route by itself: {throttle, steering, brake}. */
    static double[] autopilot(VehicleEntity vehicle, ServerLevel level) {
        if (vehicle.target >= vehicle.route.size()) vehicle.target = 0;
        Vec3 goal = vehicle.route.get(vehicle.target);
        double dx = goal.x - vehicle.getX(), dz = goal.z - vehicle.getZ(), dist = Math.sqrt(dx * dx + dz * dz);
        // got out somewhere else along the way: carry on from the nearest point
        if (dist > OFF_ROUTE_DISTANCE) {
            vehicle.legStart = -1;
            vehicle.target = nearestPoint(vehicle);
            goal = vehicle.route.get(vehicle.target);
            dx = goal.x - vehicle.getX();
            dz = goal.z - vehicle.getZ();
            dist = Math.sqrt(dx * dx + dz * dz);
        }
        int stop = stopIndex(vehicle, vehicle.target);
        if (dist < (stop >= 0 ? STOP_ARRIVE : POINT_ARRIVE)) {
            if (vehicle.docked == 0 && vehicle.legStart >= 0) {
                while (vehicle.legTicks.size() < vehicle.route.size()) vehicle.legTicks.add(0);
                vehicle.legTicks.set(vehicle.target, Math.max(1, vehicle.tickCount - vehicle.legStart));
                vehicle.legStart = -1;
            }
            if (stop >= 0 && vehicle.docked < VehicleEntity.DOCK_TICKS) {
                if (vehicle.docked == 0) {
                    vehicle.itemsMoved += TruckStations.dock(level, vehicle.stopStation.get(stop), vehicle);
                    vehicle.stopsMade++;
                    vehicle.lastStation = vehicle.stopStation.get(stop);
                }
                vehicle.docked++;
                return new double[]{0, 0, 1};
            }
            vehicle.docked = 0;
            vehicle.target = (vehicle.target + 1) % vehicle.route.size();
            vehicle.lastDist = Double.MAX_VALUE;
            vehicle.stuck = 0;
            vehicle.legStart = vehicle.tickCount;
            return new double[]{0, 0, 0};
        }
        // not getting any closer for a while (something in the way): try the next point
        if (dist < vehicle.lastDist - VehicleRecording.SAME_SPOT) {
            vehicle.lastDist = dist;
            vehicle.stuck = 0;
        } else if (++vehicle.stuck > STUCK_TICKS) {
            vehicle.target = (vehicle.target + 1) % vehicle.route.size();
            vehicle.lastDist = Double.MAX_VALUE;
            vehicle.stuck = 0;
            vehicle.legStart = -1;
        }
        float want = (float) (Math.atan2(-dx, dz) * Mth.RAD_TO_DEG);
        double off = Mth.wrapDegrees(want - vehicle.getYRot());
        double limit = vehicle.kind.maxSpeed * (Math.abs(off) > SHARP_TURN_DEGREES ? TURN_SPEED_SHARE
            : stop >= 0 && dist < APPROACH_DISTANCE ? APPROACH_SPEED_SHARE : CRUISE_SPEED_SHARE);
        // wheels turn it only while it rolls: creep forward while turning hard rather than stopping
        double throttle = vehicle.speed < limit ? 1 : 0;
        boolean brake = vehicle.speed > limit + BRAKE_MARGIN;
        // drive() turns by steering x turnDegrees x grip: aim the turn at what is off, no further
        double grip = Math.max(MIN_GRIP,
            Math.min(1, Math.abs(vehicle.speed) / (vehicle.kind.maxSpeed * VehicleDriving.FULL_GRIP_SHARE)));
        double steer = Mth.clamp(off / (vehicle.kind.turnDegrees * grip), -1, 1);
        return new double[]{throttle, steer, brake ? 1 : 0};
    }

    private static int nearestPoint(VehicleEntity vehicle) {
        int best = 0;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < vehicle.route.size(); i++) {
            double d = vehicle.route.get(i).distanceToSqr(vehicle.position());
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return best;
    }
}
