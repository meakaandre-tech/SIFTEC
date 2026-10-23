package com.meakaandre.siftec.sim;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.vehicle.VehicleEntity;
import com.meakaandre.siftec.vehicle.VehicleKind;
import com.meakaandre.siftec.vehicle.Vehicles;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Trucks, Tractors and Explorers on a recorded route keep driving while the land they are on does not tick. As soon
 * as one driving its route by itself is in a chunk that stops ticking entities, it leaves the world into the
 * simulation's record ({@link SimData.Vehicle}): there it drives each leg in the time it last took for real (or its length
 * at the autopilot's speed), burns its fuel, and stands at each Truck Station to load or unload, as {@link Sim#drive}
 * does. When where it would be is ticking land again, it comes back into the world there, with its cargo and fuel.
 * Nothing loads a chunk for it, and the map shows it where it would be ({@link #positions}).
 */
public final class SimVehicles {
    private static final Set<VehicleEntity> LOADED = Collections.newSetFromMap(new WeakHashMap<>());
    /** Ticks between two looks, while the worker is idle, for simulated vehicles that can come back into the world. */
    private static final int RETURN_TICKS = 5;
    /** How far above its route point a returning vehicle is put, so it does not start inside the ground. */
    private static final double RETURN_LIFT = 0.05;

    private SimVehicles() {
    }

    /** The ticks a leg takes, as the simulation drives it. */
    public static double legTicks(SimData.Vehicle v, int to) {
        return Sim.legTicks(v, to);
    }

    static void clear() {
        LOADED.clear();
    }

    static void register() {
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof VehicleEntity v) LOADED.add(v);
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
            if (entity instanceof VehicleEntity v) LOADED.remove(v);
        });
        // a vehicle the simulation is driving is not loaded again from its old chunk as well
        ServerEntityEvents.ALLOW_LOAD.register((entity, level, reason, fromChunk) -> {
            if (!(entity instanceof VehicleEntity)) return true;
            boolean simulated = SimData.get(level.getServer()).vehicle(entity.getUUID().toString()) != null;
            if (simulated) Siftec.LOGGER.info("SIFTEC: an old copy of vehicle {} was not loaded: the simulation drives it", entity.getUUID());
            return !simulated;
        });
        // a chunk that stops ticking entities: the vehicles in it driving their routes go on by time
        ServerChunkEvents.FULL_CHUNK_STATUS_CHANGE.register((level, chunk, from, to) -> {
            if (Factories.stopping() || !from.isOrAfter(FullChunkStatus.ENTITY_TICKING) || to.isOrAfter(FullChunkStatus.ENTITY_TICKING)) return;
            int x = chunk.getPos().getMinBlockX(), z = chunk.getPos().getMinBlockZ();
            AABB box = new AABB(x, level.getMinY(), z, x + 16, level.getMaxY() + 1, z + 16);
            for (VehicleEntity v : level.getEntitiesOfClass(VehicleEntity.class, box)) if (v.autonomous() && !v.isRemoved()) virtualize(level, v);
        });
    }

    static void tick(MinecraftServer server, long now, boolean idle) {
        if (now % 20 == 0) {
            for (VehicleEntity v : new ArrayList<>(LOADED)) {
                if (v.isRemoved() || !(v.level() instanceof ServerLevel level) || !v.autonomous()) continue;
                if (!level.isPositionEntityTicking(v.blockPosition())) virtualize(level, v);
            }
        }
        if (!idle || now % RETURN_TICKS != 0) return;
        SimData data = SimData.get(server);
        if (data.stored.vehicles.isEmpty()) return;
        for (SimData.Vehicle v : new ArrayList<>(data.stored.vehicles)) {
            ServerLevel level = Factories.level(server, v.dim);
            if (level == null) continue;
            double[] p = returnAt(v);
            BlockPos at = BlockPos.containing(p[0], p[1], p[2]);
            if (level.isLoaded(at) && level.isPositionEntityTicking(at)) materialize(level, data, v);
        }
    }

    /** Takes a vehicle out of the world into the simulation, where it goes on driving its route. */
    static void virtualize(ServerLevel level, VehicleEntity e) {
        SimData data = SimData.get(level.getServer());
        SimData.Vehicle v = new SimData.Vehicle();
        e.toSim(v);
        v.simTime = level.getGameTime();
        data.stored.vehicles.removeIf(o -> o.uuid.equals(v.uuid));
        data.stored.vehicles.add(v);
        data.setDirty();
        LOADED.remove(e);
        e.discard();
        Siftec.LOGGER.info("SIFTEC: {} {} left ticking land at {}; it drives its route by time", v.type, v.uuid, e.blockPosition().toShortString());
    }

    /**
     * Where a vehicle comes back into the world: on a point of its route it has really reached (the stop it stands at, or
     * the last point it passed), which it once drove over for real, never part way along a straight line between two
     * points, which may run through a hill or over a drop.
     */
    static double[] returnAt(SimData.Vehicle v) {
        if (v.points() < 1) return new double[]{v.x, v.y, v.z};
        int i = Sim.returnPoint(v);
        return new double[]{v.px(i), v.py(i), v.pz(i)};
    }

    /** Puts a vehicle the simulation drove back into the world, at the route point it last reached. */
    static void materialize(ServerLevel level, SimData data, SimData.Vehicle v) {
        VehicleKind kind = VehicleKind.of(v.type);
        if (kind == null) {
            data.stored.vehicles.remove(v);
            return;
        }
        VehicleEntity e = new VehicleEntity(Vehicles.type(kind), level);
        try {
            e.setUUID(UUID.fromString(v.uuid));
        } catch (IllegalArgumentException ignored) {
            Siftec.LOGGER.warn("SIFTEC: simulated vehicle has an unreadable UUID {}; it gets a new one", v.uuid, ignored);
        }
        double[] p = returnAt(v);
        // from the last point it passed it drives the rest of that leg again
        if (v.docked < 0) v.leg = 0;
        e.fromSim(v);
        e.snapTo(p[0], p[1] + RETURN_LIFT, p[2], v.yaw, 0);
        data.stored.vehicles.remove(v);
        if (!level.addFreshEntity(e)) {
            data.stored.vehicles.add(v);
            return;
        }
        data.setDirty();
        Siftec.LOGGER.info("SIFTEC: {} {} came back into ticking land at {}", v.type, v.uuid, e.blockPosition().toShortString());
    }

    /** A vehicle the map shows: where it is (or would be), which way it faces, its kind and company. */
    public record Shown(String dim, double x, double z, float yaw, String kind, String company, boolean simulated) {
    }

    /** Every vehicle on a route and every drone in flight, for the map. */
    public static List<Shown> positions(MinecraftServer server) {
        List<Shown> out = new ArrayList<>();
        for (VehicleEntity v : LOADED) {
            if (v.isRemoved()) continue;
            out.add(new Shown(v.level().dimension().identifier().toString(), v.getX(), v.getZ(), v.getYRot(), v.kind.id, v.companyId, false));
        }
        SimData data = SimData.get(server);
        for (SimData.Vehicle v : data.stored.vehicles) out.add(new Shown(v.dim, v.x, v.z, v.yaw, v.type, v.company, true));
        for (SimData.Node n : data.stored.nodes.values()) {
            if (n.kind != NodeKind.DRONE_PORT || n.state == Sim.IDLE || n.destination == null || n.total <= 0) continue;
            BlockPos home = n.blockPos(), far = BlockPos.of(n.destination);
            double done = 1 - n.ticks / (double) n.total;
            double t = n.state == Sim.OUTBOUND ? done : 1 - done;
            double x = home.getX() + 0.5 + (far.getX() - home.getX()) * t, z = home.getZ() + 0.5 + (far.getZ() - home.getZ()) * t;
            float yaw = (float) Math.toDegrees(Math.atan2(-(far.getX() - home.getX()) * (n.state == Sim.OUTBOUND ? 1 : -1),
                (far.getZ() - home.getZ()) * (n.state == Sim.OUTBOUND ? 1 : -1)));
            out.add(new Shown(n.dim, x, z, yaw, "drone", n.company, n.sim));
        }
        return out;
    }
}
