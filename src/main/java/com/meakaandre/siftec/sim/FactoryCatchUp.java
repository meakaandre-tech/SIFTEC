package com.meakaandre.siftec.sim;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.building.ControllerBlockEntity;
import com.meakaandre.siftec.building.SimAccess;
import com.meakaandre.siftec.drone.DronePortBlockEntity;
import com.meakaandre.siftec.sim.SimData.Link;
import com.meakaandre.siftec.sim.SimData.Node;
import com.meakaandre.siftec.util.Chunks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/** Hands nodes between world and simulation: the takeover as chunks stop ticking, the catch-up as they tick again. */
final class FactoryCatchUp {
    private FactoryCatchUp() {
    }

    /** Every node whose chunks stopped or started ticking goes over to the simulation, or is caught up. */
    static void transitions(MinecraftServer s, SimData data, long now) {
        List<String> gone = new ArrayList<>();
        int nodes = 0, simulated = 0;
        for (Node n : data.stored.nodes.values()) {
            nodes++;
            ServerLevel level = FactoryChunks.level(s, n.dim);
            if (level == null) {
                if (n.sim) simulated++;
                continue;
            }
            boolean ticking = FactoryChunks.ticking(level, n, Long.MIN_VALUE);
            if (n.sim) {
                if (ticking && !Factories.stopping && FactoryChunks.loaded(level, n)) {
                    if (!FactoryChunks.present(level, Buffers.level(level), n)) gone.add(n.key());
                    else catchUp(level, data, n, now);
                } else {
                    simulated++;
                }
            } else if (!ticking) {
                if (!FactoryChunks.simulates(n)) continue;
                if (!toSim(level, data, n, now)) gone.add(n.key());
                else simulated++;
            } else {
                // it ticks again before the simulation took it over: the read made as it stopped is stale now
                n.parkedAt = Long.MIN_VALUE;
                if (now % Factories.RECORD == 0 && !FactoryChunks.present(level, Buffers.level(level), n)) gone.add(n.key());
            }
        }
        for (String k : gone) {
            FactoryBorder.forget(s, data, k);
            Siftec.LOGGER.info("SIFTEC: the factory simulation forgot {} (nothing there any more)", k);
        }
        Factories.lastNodes = nodes;
        Factories.lastSimulated = simulated;
    }

    /**
     * Its chunks stopped ticking: the simulation runs it from what its buffers held then. False if it is not there any
     * more (broken while its chunk did not tick): it is to be forgotten.
     */
    private static boolean toSim(ServerLevel level, SimData data, Node n, long now) {
        long from = now;
        if (n.parkedAt != Long.MIN_VALUE) {
            // read as its last chunk stopped ticking: the simulation starts from that read, at that time
            from = Math.min(now, n.parkedAt);
            n.parkedAt = Long.MIN_VALUE;
        } else if (level.isLoaded(n.blockPos())) {
            // still loaded, just not ticking: read it once more, and check it is still there
            if (!FactoryChunks.present(level, Buffers.level(level), n)) return false;
            FactoryRecording.refresh(level, n);
        }
        start(level, n, from);
        data.setDirty();
        return true;
    }

    /** The simulation takes a node over from what it holds now, as at game time {@code from}. */
    static void start(ServerLevel level, Node n, long from) {
        n.baseIn = new HashMap<>(n.in);
        n.baseOut = new HashMap<>(n.out);
        n.baseFin = new HashMap<>(n.fin);
        n.baseFout = new HashMap<>(n.fout);
        n.baseSlots = new ArrayList<>();
        for (SimData.Slot sl : n.slots) n.baseSlots.add(new SimData.Slot(sl.key, sl.count));
        n.made = 0;
        n.mined = 0;
        n.given = 0;
        n.energy0 = n.energy;
        n.fed0 = n.fed;
        n.simTime = from;
        n.pending.clear();
        n.pendingFluid.clear();
        n.pendingAt = Long.MIN_VALUE;
        n.nextBreak = Long.MIN_VALUE;
        n.handOver = false;
        n.mixed = false;
        n.sim = true;
        if (Chunks.blockEntity(level, n.blockPos()) instanceof ControllerBlockEntity c) c.simulated = true;
        Factories.snapshots++;
    }

    /** Its chunk ticks again: what the simulation did goes into the world, and the real building takes over. */
    static void catchUp(ServerLevel level, SimData data, Node n, long now) {
        // a recipe still under way finishes in the building: its outputs are what it would have made
        if (!n.pending.isEmpty() || !n.pendingFluid.isEmpty()) {
            Sim.addAll(n.out, n.pending, 1);
            Sim.addAll(n.fout, n.pendingFluid, 1);
            n.pending.clear();
            n.pendingFluid.clear();
        }
        BlockEntity be = level.getBlockEntity(n.blockPos());
        switch (n.kind) {
            case DRONE_PORT -> {
                if (be instanceof DronePortBlockEntity port) port.fromSim(n);
            }
            default -> {
                Buffers b = Buffers.of(level, n);
                if (b != null) b.write(level, n, n.blockPos());
                if (be instanceof ControllerBlockEntity c) {
                    SimAccess.handBack(level, c, n);
                    c.simulated = false;
                }
            }
        }
        n.sim = false;
        n.parkedAt = Long.MIN_VALUE;
        n.made = 0;
        n.mined = 0;
        n.given = 0;
        n.baseIn.clear();
        n.baseOut.clear();
        n.baseFin.clear();
        n.baseFout.clear();
        n.baseSlots.clear();
        n.recordedAt = Long.MIN_VALUE;
        Factories.catchUps++;
        data.setDirty();
        if (be != null) FactoryRecording.refresh(level, n);
        // what the simulation still had on belts between this and another building that runs for real now goes on into
        // the building at the belt's end (the real belt carries nothing of it)
        MinecraftServer s = level.getServer();
        for (Link l : new ArrayList<>(data.stored.links)) {
            if (l.transit.isEmpty() || !l.from.equals(n.key()) && !l.to.equals(n.key())) continue;
            Node other = data.node(l.from.equals(n.key()) ? l.to : l.from);
            if (other == null || !other.sim) FactoryBorder.flush(s, data, l, null);
        }
    }
}
