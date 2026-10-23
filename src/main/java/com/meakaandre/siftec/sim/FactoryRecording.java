package com.meakaandre.siftec.sim;

import com.meakaandre.siftec.building.ControllerBlockEntity;
import com.meakaandre.siftec.building.PortBlock;
import com.meakaandre.siftec.building.SimAccess;
import com.meakaandre.siftec.drone.DronePortBlockEntity;
import com.meakaandre.siftec.sim.SimData.Link;
import com.meakaandre.siftec.sim.SimData.Node;
import com.meakaandre.siftec.util.Chunks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Records ticking buildings and ports for the simulation: their model, buffers, belts and pipes. */
final class FactoryRecording {
    private FactoryRecording() {
    }

    static void read(ServerLevel level, SimData data, ControllerBlockEntity c, boolean full) {
        Node n = data.node(level, c.getBlockPos());
        if (n != null && n.sim) return;
        if (n == null || n.recordedAt == Long.MIN_VALUE || full) record(level, data, c);
        else refresh(level, n);
    }

    /** Reads a building's model, its buffers and its belts and pipes. */
    static void record(ServerLevel level, SimData data, ControllerBlockEntity c) {
        Node n = data.nodeOrNew(level, c.getBlockPos(), NodeKind.BUILDING);
        if (n.sim) return;
        SimAccess.model(level, c, n);
        List<Long> chunks = SimAccess.chunks(c);
        if (!chunks.equals(n.chunks)) {
            n.chunks = chunks;
            data.version++;
        }
        refresh(level, n);
        for (String k : new ArrayList<>(n.in.keySet())) SimAccess.accepts(n, k);
        // what the belts and pipes into it bring: fuel to a generator, points to a Sink
        for (Link l : data.stored.links) {
            if (!l.to.equals(n.key())) continue;
            Node from = data.node(l.from);
            if (from != null) for (String k : outputs(from)) SimAccess.accepts(n, k);
        }
        links(level, data, c, n);
        n.recordedAt = level.getGameTime();
        data.setDirty();
    }

    /** Reads what a ticking node's buffers hold now (and a building's power state), for when its chunk stops ticking. */
    static void refresh(ServerLevel level, Node n) {
        refresh(level, Buffers.level(level), n);
    }

    /** Reads a node's buffers; false if they could not be read (not there, or a cell of it in a chunk not loaded). */
    static boolean refresh(ServerLevel level, Buffers.Reach reach, Node n) {
        if (!reach.has(n.blockPos())) return false;
        BlockEntity be = reach.be(n.blockPos());
        if (n.kind == NodeKind.DRONE_PORT) {
            if (!(be instanceof DronePortBlockEntity port)) return false;
            port.toSim(n);
            return true;
        }
        Buffers b = Buffers.of(level, reach, n);
        if (b == null) return false;
        b.read(n);
        // the machines are read through the same reach: a generator whose chunk unloads outright has its burner fuel
        // read as the chunk leaves, as one whose chunk only stops ticking
        if (be instanceof ControllerBlockEntity c) SimAccess.snapshot(level, c, n, reach::has, reach::be);
        return true;
    }

    /** The links from a building's output ports, as its belts and pipes run now. */
    private static void links(ServerLevel level, SimData data, ControllerBlockEntity c, Node n) {
        String key = n.key();
        Map<String, Link> old = new HashMap<>();
        for (Link l : data.stored.links) if (l.from.equals(key)) old.put(l.fromPort + ">" + l.to + ">" + l.toPort, l);
        List<Link> made = new ArrayList<>();
        Set<String> outputs = outputs(n);
        for (SimAccess.Port p : SimAccess.ports(c)) {
            if (p.kind() != PortBlock.Kind.ITEM_OUT && p.kind() != PortBlock.Kind.FLUID_OUT) continue;
            boolean fluid = p.kind() == PortBlock.Kind.FLUID_OUT;
            List<Tracer.End> ends = fluid ? Tracer.fluids(level, p.pos(), p.facing()) : Tracer.items(level, p.pos(), p.facing());
            for (Tracer.End e : ends) {
                Node to;
                if (e.inventory()) {
                    to = data.nodeOrNew(level, e.at(), NodeKind.INVENTORY);
                    to.mode = NodeMode.STORE;
                    if (!to.sim) refresh(level, to);
                } else {
                    if (e.node().equals(c.getBlockPos())) continue;
                    to = data.nodeOrNew(level, e.node(), NodeKind.BUILDING);
                    // a building it has not read yet (or never will, as the HUB): known by its id until it is
                    if (to.building.isEmpty() && Chunks.blockEntity(level, e.node()) instanceof ControllerBlockEntity tc) to.building = tc.buildingId;
                }
                String id = p.pos().asLong() + ">" + to.key() + ">" + e.at().asLong();
                Link l = old.remove(id);
                if (l == null) {
                    l = new Link();
                    l.dim = n.dim;
                    l.from = key;
                    l.to = to.key();
                    l.fromPort = p.pos().asLong();
                    l.toPort = e.at().asLong();
                }
                l.fluid = fluid;
                l.rate = e.rate();
                l.delay = e.delay();
                l.cap = Math.max(1, e.cap());
                l.share = e.share();
                l.recordedAt = level.getGameTime();
                made.add(l);
                // what it brings is fuel to a generator, points to a Sink
                for (String k : outputs) SimAccess.accepts(to, k);
            }
        }
        // links that no longer run: what was on them goes back into the building it came from (or is dropped there)
        for (Link l : old.values()) {
            for (SimData.Transit t : l.transit) {
                long back = Buffers.putBack(level, n, t.key, t.n, l.fluid);
                if (back < t.n && !l.fluid) FactoryBorder.drop(level, n.blockPos(), t.key, t.n - back);
            }
            l.transit.clear();
        }
        data.stored.links.removeIf(l -> l.from.equals(key));
        data.stored.links.addAll(made);
    }

    /** Everything a node may put out: its recipes' outputs, its ore, its fluid, what it holds. */
    static Set<String> outputs(Node n) {
        Set<String> out = new HashSet<>();
        for (SimData.Recipe r : n.recipes) {
            out.addAll(r.out.keySet());
            out.addAll(r.fout.keySet());
        }
        if (!n.mineItem.isEmpty()) out.add(n.mineItem);
        if (!n.fluidOut.isEmpty()) out.add(n.fluidOut);
        out.addAll(n.out.keySet());
        return out;
    }

    static void read(ServerLevel level, SimData data, DronePortBlockEntity port) {
        Node n = data.nodeOrNew(level, port.getBlockPos(), NodeKind.DRONE_PORT);
        if (n.sim) return;
        n.mode = NodeMode.PORT;
        n.parkedAt = Long.MIN_VALUE;
        port.toSim(n);
        n.recordedAt = level.getGameTime();
    }
}
