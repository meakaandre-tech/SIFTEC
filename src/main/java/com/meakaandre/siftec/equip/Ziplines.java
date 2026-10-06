package com.meakaandre.siftec.equip;

import com.meakaandre.siftec.power.PoleBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Riding Power Lines: use a wrench on a pole (after the Zipline research). The rider sits on an invisible carrier that the server slides from pole to
 * pole; at each pole it carries on down the line that runs straightest, and stops where there is none.
 * Sneak to drop off.
 */
public final class Ziplines {
    private static final double SPEED = 0.9, HANG = 2.3;
    public static final String TAG = "siftec_zipline";

    private static final class Ride {
        ServerPlayer rider;
        Display carrier;
        BlockPos from, to;
        double travelled;
    }

    private static final List<Ride> RIDES = new ArrayList<>();

    private Ziplines() {
    }

    public static void clear() {
        RIDES.clear();
    }

    private static Vec3 top(BlockPos pole) {
        return new Vec3(pole.getX() + 0.5, pole.getY() + 0.95 - HANG, pole.getZ() + 0.5);
    }

    /** The pole down the line from this one that best continues the direction given, or null. */
    private static BlockPos next(ServerLevel level, BlockPos pole, Vec3 heading, BlockPos cameFrom, double leastDot) {
        if (!(level.getBlockEntity(pole) instanceof PoleBlockEntity entity)) return null;
        BlockPos best = null;
        double bestDot = leastDot;
        for (BlockPos offset : entity.lines) {
            BlockPos other = pole.offset(offset);
            if (other.equals(cameFrom)) continue;
            Vec3 way = new Vec3(offset.getX(), 0, offset.getZ());
            if (way.lengthSqr() < 0.01) continue;
            double dot = way.normalize().dot(heading);
            if (dot > bestDot) {
                bestDot = dot;
                best = other;
            }
        }
        return best;
    }

    public static boolean start(ServerPlayer player, BlockPos pole) {
        ServerLevel level = player.level();
        Vec3 look = player.getLookAngle();
        Vec3 heading = new Vec3(look.x, 0, look.z);
        if (heading.lengthSqr() < 0.01) heading = new Vec3(1, 0, 0);
        // any line roughly ahead of where the player is looking will do to set off on
        BlockPos to = next(level, pole, heading.normalize(), null, -0.2);
        if (to == null) return false;
        Display carrier = net.minecraft.world.entity.EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.TRIGGERED);
        if (carrier == null) return false;
        Vec3 at = top(pole);
        carrier.setPos(at.x, at.y, at.z);
        ((com.meakaandre.siftec.mixin.DisplayInvoker) carrier).siftec$glide(2);
        level.addFreshEntity(carrier);
        carrier.addTag(TAG);
        if (!player.startRiding(carrier, true, true)) {
            carrier.discard();
            return false;
        }
        Ride ride = new Ride();
        ride.rider = player;
        ride.carrier = carrier;
        ride.from = pole;
        ride.to = to;
        RIDES.add(ride);
        return true;
    }

    public static void tick(MinecraftServer server) {
        for (Iterator<Ride> it = RIDES.iterator(); it.hasNext(); ) {
            Ride ride = it.next();
            ServerLevel level = (ServerLevel) ride.carrier.level();
            if (ride.rider.isRemoved() || ride.carrier.isRemoved() || ride.rider.getVehicle() != ride.carrier) {
                ride.carrier.discard();
                it.remove();
                continue;
            }
            Vec3 a = top(ride.from), b = top(ride.to);
            double length = a.distanceTo(b);
            ride.travelled += SPEED;
            if (ride.travelled >= length) {
                BlockPos onward = next(level, ride.to, new Vec3(b.x - a.x, 0, b.z - a.z).normalize(), ride.from, 0.5);
                if (onward == null) {
                    ride.rider.stopRiding();
                    ride.rider.teleportTo(b.x, ride.to.getY() + 1.0, b.z + 0.0);
                    ride.rider.resetFallDistance();
                    ride.carrier.discard();
                    it.remove();
                    continue;
                }
                ride.travelled -= length;
                ride.from = ride.to;
                ride.to = onward;
                a = top(ride.from);
                b = top(ride.to);
                length = a.distanceTo(b);
            }
            double t = Math.min(1, ride.travelled / Math.max(0.01, length));
            // the line sags in the middle, the same curve the sparks follow
            double sag = Math.sin(t * Math.PI) * Math.min(1.5, length / 12);
            ride.carrier.setPos(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t - sag, a.z + (b.z - a.z) * t);
            ride.rider.resetFallDistance();
        }
    }
}
