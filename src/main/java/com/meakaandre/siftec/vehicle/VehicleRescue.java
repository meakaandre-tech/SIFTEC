package com.meakaandre.siftec.vehicle;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.building.TruckStations;
import com.meakaandre.siftec.claim.Claims;
import com.meakaandre.siftec.node.NodeSavedData;
import com.meakaandre.siftec.prefab.Prefabs;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

/** Putting back a vehicle that fell out of the world: at its last station's dock, beside its HUB, or at world spawn. */
final class VehicleRescue {
    /** How high over the ground a vehicle that fell out of the world is put back. */
    private static final double RESCUE_LIFT = 0.1;

    private VehicleRescue() {
    }

    /** Puts the vehicle back where {@link VehicleEntity#onBelowWorld} says. True if it was put somewhere. */
    static boolean returnHome(VehicleEntity vehicle, ServerLevel level) {
        vehicle.ejectPassengers();
        ServerLevel to = level;
        Vec3 spot = null;
        if (vehicle.lastStation != null) {
            level.getChunk(vehicle.lastStation);
            var station = TruckStations.at(level, vehicle.lastStation);
            if (station != null && TruckStations.serves(station, vehicle)) spot = TruckStations.dock(station);
        }
        if (spot == null) {
            var hub = Claims.hubOf(level.getServer(), vehicle.companyId);
            ServerLevel hubLevel = hub == null ? null : level.getServer().getLevel(hub.dimension());
            if (hubLevel != null) {
                to = hubLevel;
                spot = besideHub(hubLevel, hub.pos());
            }
        }
        if (spot == null) {
            to = level.getServer().overworld();
            var origin = NodeSavedData.get(level.getServer());
            int x = origin.hasOrigin() ? origin.originX() : 0, z = origin.hasOrigin() ? origin.originZ() : 0;
            to.getChunk(new BlockPos(x, 0, z));
            spot = new Vec3(x + 0.5, to.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z + 0.5);
        }
        vehicle.speed = 0;
        vehicle.docked = 0;
        vehicle.lastDist = Double.MAX_VALUE;
        vehicle.stuck = 0;
        vehicle.setDeltaMovement(Vec3.ZERO);
        vehicle.resetFallDistance();
        if (to == level) {
            vehicle.setPos(spot.x, spot.y + RESCUE_LIFT, spot.z);
        } else if (vehicle.teleport(new TeleportTransition(to, new Vec3(spot.x, spot.y + RESCUE_LIFT, spot.z),
            Vec3.ZERO, vehicle.getYRot(), vehicle.getXRot(), TeleportTransition.DO_NOTHING)) == null) {
            return false;
        }
        Siftec.LOGGER.info("SIFTEC: {} fell out of the world; put back at {} {}", vehicle.kind.id,
            to.dimension().identifier(), BlockPos.containing(spot).toShortString());
        return true;
    }

    /** A spot on the ground just outside the HUB that stands at this terminal (on the ground by the terminal if none is found). */
    private static Vec3 besideHub(ServerLevel level, BlockPos terminal) {
        level.getChunk(terminal);
        int x = terminal.getX(), z = terminal.getZ();
        var hub = Prefabs.hubAt(level, terminal);
        if (hub != null && hub.anchor != null && hub.facing != null) {
            var box = Prefabs.bounds(new Prefabs.Spot(Prefabs.Kind.HUB, hub.anchor, hub.facing));
            x = (int) Math.floor(box.maxX) + 2;
            z = (int) Math.floor((box.minZ + box.maxZ) / 2);
            level.getChunk(new BlockPos(x, 0, z));
        }
        return new Vec3(x + 0.5, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z + 0.5);
    }
}
