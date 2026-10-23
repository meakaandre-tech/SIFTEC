package com.meakaandre.siftec.vehicle;

import com.meakaandre.siftec.building.TruckStations;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Recording a vehicle's route while it is driven: its points, and a stop at each Truck Station dock it passes. */
final class VehicleRecording {
    /** Blocks between the points of a recorded route. */
    static final double POINT_SPACING = 2.0;
    /** The fewest points a recorded route keeps. */
    private static final int MIN_ROUTE_POINTS = 3;
    /** Points it must have recorded since its last stop at a station before that station counts as a new stop. */
    private static final int RESTOP_POINTS = 6;
    /** Distances under this count as the same place, or as no progress. */
    static final double SAME_SPOT = 0.01;

    private VehicleRecording() {
    }

    /** V: starts recording a new route, or finishes the one being recorded. */
    static void toggleRecording(VehicleEntity vehicle, @Nullable ServerPlayer driver) {
        if (!vehicle.recording) {
            vehicle.recording = true;
            vehicle.legTicks.clear();
            vehicle.route.clear();
            vehicle.stopAt.clear();
            vehicle.stopStation.clear();
            vehicle.route.add(vehicle.position());
            if (driver != null) driver.sendSystemMessage(Component.translatable("siftec.vehicle.record_start"));
            return;
        }
        vehicle.recording = false;
        if (vehicle.route.size() < MIN_ROUTE_POINTS) {
            vehicle.route.clear();
            vehicle.stopAt.clear();
            vehicle.stopStation.clear();
            if (driver != null) driver.sendSystemMessage(Component.translatable("siftec.vehicle.record_short"));
            return;
        }
        vehicle.target = 0;
        vehicle.lastDist = Double.MAX_VALUE;
        vehicle.stuck = 0;
        if (driver != null) driver.sendSystemMessage(Component.translatable("siftec.vehicle.record_done",
            vehicle.route.size(), vehicle.stopAt.size(), vehicle.getName()));
    }

    /** While recording: a point every couple of blocks, and a stop at each Truck Station dock it drives through. */
    static void record(VehicleEntity vehicle, ServerLevel level) {
        Vec3 here = vehicle.position();
        if (here.distanceTo(vehicle.route.getLast()) >= POINT_SPACING) vehicle.route.add(here);
        BlockPos station = TruckStations.near(level, here, vehicle);
        if (station == null) return;
        boolean again = !vehicle.stopStation.isEmpty() && vehicle.stopStation.getLast().equals(station)
            && vehicle.route.size() - 1 - vehicle.stopAt.getLast() < RESTOP_POINTS;
        if (again) return;
        if (here.distanceTo(vehicle.route.getLast()) > SAME_SPOT) vehicle.route.add(here);
        vehicle.stopAt.add(vehicle.route.size() - 1);
        vehicle.stopStation.add(station);
        vehicle.lastStation = station;
    }
}
