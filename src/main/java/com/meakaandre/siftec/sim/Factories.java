package com.meakaandre.siftec.sim;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.building.BuildingDef;
import com.meakaandre.siftec.building.ControllerBlockEntity;
import com.meakaandre.siftec.building.PortBlock;
import com.meakaandre.siftec.building.SimAccess;
import com.meakaandre.siftec.drone.DronePortBlockEntity;
import com.meakaandre.siftec.power.GridStats;
import com.meakaandre.siftec.power.PowerGrids;
import com.meakaandre.siftec.power.PowerMemory;
import com.meakaandre.siftec.power.WireMap;
import com.meakaandre.siftec.sim.SimData.Link;
import com.meakaandre.siftec.sim.SimData.Node;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Always-running factories (work package 17): every Build Gun building, Drone Port and the inventories belts end in
 * keeps working while its chunk does not tick, without any chunk being loaded for it.
 * <ul>
 * <li>While a building's chunk ticks, it runs for real, and is recorded ({@link #record}): what it makes and at what
 * rate, where its belts and pipes go ({@link Tracer}), its wire connectors; every half second, what its buffers hold.</li>
 * <li>When its chunk stops ticking (unloaded, or loaded but out of simulation distance), the simulation takes over from
 * those buffers: groups of linked buildings are stepped together twice a second on a worker thread ({@link Sim}).</li>
 * <li>When its chunk ticks again, the catch-up writes what changed into the building in one go: its output buffers
 * filled, its inputs and fuel used up, its storage, fuse and counters ({@link Buffers}, {@link SimAccess#handBack}).</li>
 * </ul>
 * The server thread only moves things across the border between ticking and simulated buildings, pays the Sinks and
 * starts the next step; the time it spends on that each tick, and what each step takes on the worker, are kept for the
 * debug overlay ({@link #stats()}).
 */
public final class Factories {
    /** Ticks between two simulation steps. */
    public static final int STEP = 10;
    /** Ticks between two full recordings of a ticking building (its model and belts); its buffers are read every {@link #STEP}. */
    static final int RECORD = 100;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "SIFTEC factory simulation");
        t.setDaemon(true);
        return t;
    });
    private static @Nullable Future<?> running;
    private static List<Sim.Group> runningGroups = List.of();
    private static long runningNanos, runningStarted;
    private static boolean stopping;
    private static @Nullable MinecraftServer server;
    /** Nodes in mixed grids (balanced by PowerGrids), by key, with the connector their grid is found by. */
    private static final Map<String, Long> MIXED = new HashMap<>();
    /** The power grids worked out for the last step, kept while nothing changed. */
    private static final Map<ServerLevel, List<SimPower.Grid>> GRIDS = new IdentityHashMap<>();
    private static final Map<ServerLevel, String> GRIDS_FOR = new IdentityHashMap<>();

    // ---- what the debug overlay shows
    private static double mainAvgNanos, workerAvgNanos;
    private static long mainLastNanos, workerLastNanos;
    private static int lastNodes, lastSimulated, lastGroups, lastVehicles;
    private static long catchUps, snapshots;

    private Factories() {
    }

    /** The simulation's figures: buildings known and simulated, groups, vehicles, and the time it takes. */
    public record Stats(int nodes, int simulated, int groups, int vehicles, double mainMsPerTick, double workerMsPerStep,
                        double workerMsPerTick, long catchUps, long snapshots) {
        public String line() {
            return String.format(java.util.Locale.ROOT, "SIFTEC factories: %d/%d simulated in %d groups, %d vehicles; %.3f ms/tick (server %.3f, worker %.3f a step)",
                simulated, nodes, groups, vehicles, mainMsPerTick + workerMsPerTick, mainMsPerTick, workerMsPerStep);
        }
    }

    public static Stats stats() {
        return new Stats(lastNodes, lastSimulated, lastGroups, lastVehicles, mainAvgNanos / 1e6, workerAvgNanos / 1e6,
            workerAvgNanos / 1e6 / STEP, catchUps, snapshots);
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            server = s;
            stopping = false;
            Keys.serverStarted(s);
            Recipes.forget();
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
            join();
            stopping = true;
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(s -> {
            join();
            server = null;
            LEVELS.clear();
            MIXED.clear();
            GRIDS.clear();
            GRIDS_FOR.clear();
            runningGroups = List.of();
            Keys.serverStopped();
            Recipes.forget();
            SimVehicles.clear();
        });
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((s, manager, ok) -> Recipes.forget());
        ServerTickEvents.END_SERVER_TICK.register(Factories::tick);
        SimVehicles.register();
        SimStatsPayload.register();
    }

    public static boolean stopping() {
        return stopping;
    }

    /** Waits for a step the worker is running (the save, a catch-up), and takes its results. */
    public static void join() {
        Future<?> f = running;
        if (f == null) return;
        try {
            f.get();
        } catch (Exception e) {
            Siftec.LOGGER.error("SIFTEC: a factory simulation step failed", e);
        }
        if (running == f) {
            running = null;
            MinecraftServer s = server;
            if (s != null) applyResults(s);
        }
    }

    private static boolean busy() {
        return running != null && !running.isDone();
    }

    // ------------------------------------------------------------------------------------------------ each tick

    private static void tick(MinecraftServer s) {
        if (stopping) return;
        long t0 = System.nanoTime();
        long now = s.overworld().getGameTime();
        if (running != null && running.isDone()) join();
        if (running == null) {
            SimData data = SimData.get(s);
            transitions(s, data, now);
            if (now % STEP == 0) {
                boundary(s, data, now);
                submit(s, data, now);
            }
        }
        SimVehicles.tick(s, now, running == null);
        long took = System.nanoTime() - t0;
        mainLastNanos = took;
        mainAvgNanos = mainAvgNanos * 0.98 + took * 0.02;
        if (now % 20 == 0) SimStatsPayload.send(s, stats());
    }

    /** Every node whose chunk stopped or started ticking goes over to the simulation, or is caught up. */
    private static void transitions(MinecraftServer s, SimData data, long now) {
        List<String> gone = new ArrayList<>();
        int nodes = 0, simulated = 0;
        for (Node n : data.stored.nodes.values()) {
            nodes++;
            ServerLevel level = level(s, n.dim);
            if (level == null) {
                if (n.sim) simulated++;
                continue;
            }
            BlockPos pos = n.blockPos();
            boolean ticking = level.isLoaded(pos) && level.shouldTickBlocksAt(pos);
            if (n.sim) {
                if (ticking && !stopping) {
                    if (!present(level, n)) gone.add(n.key());
                    else catchUp(level, data, n, now);
                } else {
                    simulated++;
                }
            } else if (!ticking) {
                if (n.mode.equals("none") && n.su <= 0 && !n.kind.equals("drone_port")) continue;
                toSim(level, data, n, now);
                simulated++;
            } else if (now % RECORD == 0 && !present(level, n)) {
                gone.add(n.key());
            }
        }
        for (String k : gone) {
            data.remove(k);
            Siftec.LOGGER.info("SIFTEC: the factory simulation forgot {} (nothing there any more)", k);
        }
        lastNodes = nodes;
        lastSimulated = simulated;
    }

    private static final Map<String, ServerLevel> LEVELS = new HashMap<>();

    static @Nullable ServerLevel level(MinecraftServer s, String dim) {
        ServerLevel known = LEVELS.get(dim);
        if (known != null && known.getServer() == s) return known;
        for (ServerLevel level : s.getAllLevels()) {
            if (level.dimension().identifier().toString().equals(dim)) {
                LEVELS.put(dim, level);
                return level;
            }
        }
        return null;
    }

    /** True if what the node stands for is still there (its chunk is loaded). */
    private static boolean present(ServerLevel level, Node n) {
        BlockEntity be = level.getBlockEntity(n.blockPos());
        return switch (n.kind) {
            case "drone_port" -> be instanceof DronePortBlockEntity;
            case "inventory" -> net.fabricmc.fabric.api.transfer.v1.item.ItemStorage.SIDED.find(level, n.blockPos(), null) != null;
            default -> be instanceof ControllerBlockEntity c && c.buildingId.equals(n.building);
        };
    }

    /** Its chunk stopped ticking: the simulation runs it from what its buffers last held. */
    private static void toSim(ServerLevel level, SimData data, Node n, long now) {
        // read it once more if it is still there (loaded, just not ticking)
        if (level.isLoaded(n.blockPos())) refresh(level, n);
        n.baseIn = new HashMap<>(n.in);
        n.baseOut = new HashMap<>(n.out);
        n.baseFin = new HashMap<>(n.fin);
        n.baseFout = new HashMap<>(n.fout);
        n.made = 0;
        n.mined = 0;
        n.given = 0;
        n.energy0 = n.energy;
        n.fed0 = n.fed;
        n.simTime = now;
        n.pending.clear();
        n.pendingFluid.clear();
        n.pendingAt = Long.MIN_VALUE;
        n.nextBreak = Long.MIN_VALUE;
        n.handOver = false;
        n.sim = true;
        snapshots++;
        data.setDirty();
    }

    /** Its chunk ticks again: what the simulation did goes into the world, and the real building takes over. */
    private static void catchUp(ServerLevel level, SimData data, Node n, long now) {
        // a recipe still under way finishes in the building: its outputs are what it would have made
        if (!n.pending.isEmpty() || !n.pendingFluid.isEmpty()) {
            Sim.addAll(n.out, n.pending, 1);
            Sim.addAll(n.fout, n.pendingFluid, 1);
            n.pending.clear();
            n.pendingFluid.clear();
        }
        BlockEntity be = level.getBlockEntity(n.blockPos());
        switch (n.kind) {
            case "drone_port" -> {
                if (be instanceof DronePortBlockEntity port) port.fromSim(n);
            }
            default -> {
                Buffers b = Buffers.of(level, n);
                if (b != null) b.write(level, n, n.blockPos());
                if (be instanceof ControllerBlockEntity c) SimAccess.handBack(level, c, n);
            }
        }
        n.sim = false;
        n.made = 0;
        n.mined = 0;
        n.given = 0;
        n.baseIn.clear();
        n.baseOut.clear();
        n.baseFin.clear();
        n.baseFout.clear();
        n.recordedAt = Long.MIN_VALUE;
        catchUps++;
        data.setDirty();
        if (be != null) refresh(level, n);
    }

    // ------------------------------------------------------------------------------------------------ recording

    /** A building's controller ticked: it is recorded now and then, and its buffers read every half second. */
    public static void building(ServerLevel level, ControllerBlockEntity c) {
        if (stopping || !SimAccess.simulated(c.def())) return;
        long now = level.getGameTime();
        long phase = now + (c.getBlockPos().asLong() & 0x7FFF);
        if (phase % STEP != 0) return;
        if (busy()) return;
        SimData data = SimData.get(level.getServer());
        Node n = data.node(level, c.getBlockPos());
        if (n != null && n.sim) return;
        if (n == null || n.recordedAt == Long.MIN_VALUE || phase % RECORD == 0) record(level, data, c);
        else refresh(level, n);
    }

    /** Reads a building's model, its buffers and its belts and pipes. */
    static void record(ServerLevel level, SimData data, ControllerBlockEntity c) {
        Node n = data.nodeOrNew(level, c.getBlockPos(), "building");
        if (n.sim) return;
        SimAccess.model(level, c, n);
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
        BlockEntity be = level.getBlockEntity(n.blockPos());
        if (n.kind.equals("drone_port")) {
            if (be instanceof DronePortBlockEntity port) port.toSim(n);
            return;
        }
        Buffers b = Buffers.of(level, n);
        if (b != null) b.read(n);
        if (be instanceof ControllerBlockEntity c) SimAccess.snapshot(level, c, n);
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
                    to = data.nodeOrNew(level, e.at(), "inventory");
                    to.mode = "store";
                    if (!to.sim) refresh(level, to);
                } else {
                    if (e.node().equals(c.getBlockPos())) continue;
                    to = data.nodeOrNew(level, e.node(), "building");
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
        // links that no longer run: what was on them goes back to where it came from
        for (Link l : old.values()) for (SimData.Transit t : l.transit) Sim.add(l.fluid ? n.fout : n.out, t.key, t.n);
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

    /** A Drone Port ticked (once a second): it is a node, with its flight kept up to date for the map. */
    public static void port(ServerLevel level, DronePortBlockEntity port) {
        if (stopping || busy()) return;
        SimData data = SimData.get(level.getServer());
        Node n = data.nodeOrNew(level, port.getBlockPos(), "drone_port");
        if (n.sim) return;
        n.mode = "port";
        port.toSim(n);
        n.recordedAt = level.getGameTime();
    }

    /**
     * A drone from a ticking port arrives at a port whose chunk does not tick: the packages go into that port's record in
     * the simulation, as far as its arrival slots hold them. Returns how many stacks are left, or -1 if no port is known
     * there.
     */
    public static int deliverToPort(ServerLevel level, BlockPos at, List<ItemStack> cargo) {
        join();
        SimData data = SimData.get(level.getServer());
        Node far = data.node(level, at);
        if (far == null || !far.kind.equals("drone_port")) return -1;
        if (!far.sim && level.isLoaded(at)) return -1;
        Sim.pad(far.slots, Sim.PORT_SIZE);
        for (int i = 0; i < cargo.size(); i++) {
            ItemStack s = cargo.get(i);
            if (s.isEmpty()) continue;
            long left = Sim.addSlots(far.slots, Sim.PORT_IN, Sim.PORT_SIZE, Keys.of(s), s.getCount());
            cargo.set(i, left <= 0 ? ItemStack.EMPTY : s.copyWithCount((int) left));
        }
        cargo.removeIf(ItemStack::isEmpty);
        data.setDirty();
        return cargo.size();
    }

    /** A building was taken down: the simulation forgets it. */
    public static void removed(ServerLevel level, BlockPos controller) {
        join();
        SimData.get(level.getServer()).remove(SimData.key(level, controller));
    }

    // ------------------------------------------------------------------------------------------------ the border

    /**
     * What crosses between ticking and simulated buildings, on the server thread: a belt from a ticking building to a
     * simulated one takes from the ticking one's output as fast as it carries.
     */
    private static void boundary(MinecraftServer s, SimData data, long now) {
        for (Link l : data.stored.links) {
            Node from = data.node(l.from), to = data.node(l.to);
            if (from == null || to == null || from.sim || !to.sim) continue;
            ServerLevel level = level(s, l.dim);
            if (level == null || !level.isLoaded(from.blockPos()) || !level.shouldTickBlocksAt(from.blockPos())) continue;
            long dt = l.last == Long.MIN_VALUE ? 0 : Math.max(0, now - l.last);
            l.last = now;
            l.allowance = Math.min(l.allowance + l.rate * l.share * dt, Math.max(1, l.rate * l.share * 40));
            long most = Math.min((long) Math.floor(l.allowance + 1e-9), Math.max(0, l.cap - l.carrying()));
            if (most <= 0) continue;
            Map<String, Long> got = Buffers.extract(level, from, most, l.fluid);
            for (Map.Entry<String, Long> e : got.entrySet()) {
                l.transit.add(new SimData.Transit(now + l.delay, e.getKey(), e.getValue()));
                l.allowance -= e.getValue();
            }
        }
    }

    /** After a step: deliveries to ticking buildings, Sink points, drones landing at ticking ports, mixed grids. */
    private static void applyResults(MinecraftServer s) {
        long t0 = System.nanoTime();
        SimData data = SimData.get(s);
        long now = s.overworld().getGameTime();
        for (Sim.Group g : runningGroups) {
            for (Node n : g.nodes) {
                if (!n.sunk.isEmpty()) pay(s, n);
                if (n.handOver) handOver(s, data, n);
            }
            for (Link l : g.links) {
                Node to = data.node(l.to);
                if (to == null || to.sim) continue;
                ServerLevel level = level(s, l.dim);
                if (level == null || !level.isLoaded(to.blockPos())) continue;
                var it = l.transit.iterator();
                while (it.hasNext()) {
                    SimData.Transit t = it.next();
                    if (t.at > now) break;
                    long put = Buffers.insert(level, to, t.key, t.n, l.fluid);
                    t.n -= put;
                    if (t.n > 0) break;
                    it.remove();
                }
            }
        }
        for (Map.Entry<String, Long> e : MIXED.entrySet()) {
            Node n = data.node(e.getKey());
            ServerLevel level = n == null ? null : level(s, n.dim);
            if (n == null || level == null || !n.sim) continue;
            // a generator or Power Storage in such a grid is its remembered self (PowerMemory burns and charges it, and
            // hands that over when it is back); a consumer asks for what the simulation says it needs
            if (Sim.generator(n) || n.power.equals("storage")) continue;
            PowerMemory.Remembered r = PowerMemory.get(level).at(level, n.blockPos());
            if (r != null) r.simulateDemand(Sim.demand(n, now));
        }
        data.setDirty();
        mainLastNanos += System.nanoTime() - t0;
    }

    private static void pay(MinecraftServer s, Node n) {
        long points = 0;
        for (Map.Entry<String, Long> e : n.sunk.entrySet()) {
            Long worth = n.worth.get(e.getKey());
            points += (worth == null ? SimAccess.worth(e.getKey()) : worth) * e.getValue();
        }
        n.sunk.clear();
        if (points > 0 && !n.company.isEmpty()) com.meakaandre.siftec.sink.Sink.pay(s, n.company, points);
    }

    /** A simulated drone at a ticking port: the packages go into that port's arrivals, and the drone heads home. */
    private static void handOver(MinecraftServer s, SimData data, Node n) {
        n.handOver = false;
        ServerLevel level = level(s, n.dim);
        if (level == null || n.destination == null) return;
        BlockPos at = BlockPos.of(n.destination);
        if (!level.isLoaded(at) || !(level.getBlockEntity(at) instanceof DronePortBlockEntity other)) return;
        List<SimData.Slot> left = new ArrayList<>();
        for (SimData.Slot c : n.cargo) {
            ItemStack rest = other.items.add(Keys.stack(c.key, c.count), com.meakaandre.siftec.drone.PortContainer.IN, com.meakaandre.siftec.drone.PortContainer.SIZE);
            if (!rest.isEmpty()) left.add(new SimData.Slot(c.key, rest.getCount()));
        }
        n.cargo = left;
        if (left.isEmpty()) {
            n.state = Sim.HOMEBOUND;
            n.ticks = n.total;
        }
        data.setDirty();
    }

    // ------------------------------------------------------------------------------------------------ the step

    /** Works out the groups of simulated buildings and hands the step to the worker. */
    private static void submit(MinecraftServer s, SimData data, long now) {
        List<Sim.Group> groups = groups(s, data);
        lastGroups = groups.size();
        if (groups.isEmpty()) return;
        runningGroups = groups;
        runningStarted = System.nanoTime();
        running = WORKER.submit(() -> {
            long t0 = System.nanoTime();
            for (Sim.Group g : groups) {
                try {
                    Sim.step(g, now);
                } catch (RuntimeException e) {
                    Siftec.LOGGER.error("SIFTEC: a factory group failed to step", e);
                }
            }
            long took = System.nanoTime() - t0;
            workerLastNanos = took;
            workerAvgNanos = workerAvgNanos == 0 ? took : workerAvgNanos * 0.9 + took * 0.1;
        });
    }

    /** The groups of linked simulated buildings (with their links, power grids and vehicles) for a step. */
    static List<Sim.Group> groups(MinecraftServer s, SimData data) {
        Map<String, Map<String, Node>> byDim = new HashMap<>();
        for (Node n : data.stored.nodes.values()) byDim.computeIfAbsent(n.dim, k -> new LinkedHashMap<>()).put(n.key(), n);
        if (data == SimData.get(s)) MIXED.clear();
        List<Sim.Group> groups = new ArrayList<>();
        int vehicles = 0;
        for (Map.Entry<String, Map<String, Node>> dimEntry : byDim.entrySet()) {
            ServerLevel level = level(s, dimEntry.getKey());
            Map<String, Node> all = dimEntry.getValue();
            List<Node> sim = new ArrayList<>();
            for (Node n : all.values()) if (n.sim) sim.add(n);
            List<SimData.Vehicle> vs = new ArrayList<>();
            for (SimData.Vehicle v : data.stored.vehicles) if (v.dim.equals(dimEntry.getKey())) vs.add(v);
            vehicles += vs.size();
            if (sim.isEmpty() && vs.isEmpty()) continue;
            Union u = new Union();
            for (Node n : sim) u.find(n.key());
            // power: grids all simulated are balanced in the step; mixed ones by PowerGrids, read here
            List<SimPower.Grid> grids = level == null ? List.of() : grids(level, data, sim);
            Set<String> onGrid = new HashSet<>();
            for (SimPower.Grid g : grids) {
                for (Node n : g.members()) onGrid.add(n.key());
                if (g.mixed()) {
                    for (Node n : g.members()) mixedBefore(level, n);
                } else {
                    for (int i = 1; i < g.members().size(); i++) u.union(g.members().getFirst().key(), g.members().get(i).key());
                }
            }
            // a consumer on no grid has no power
            for (Node n : sim) if (!onGrid.contains(n.key()) && n.su > 0) n.powered = false;
            for (Link l : data.stored.links) {
                if (!l.dim.equals(dimEntry.getKey())) continue;
                Node from = all.get(l.from), to = all.get(l.to);
                if (from != null && to != null && from.sim && to.sim) u.union(l.from, l.to);
            }
            for (Node n : sim) {
                if (n.kind.equals("drone_port") && n.destination != null) {
                    Node far = all.get(SimData.key(n.dim, n.destination));
                    if (far != null && far.sim) u.union(n.key(), far.key());
                }
            }
            Map<String, String> vehicleRoot = new HashMap<>();
            for (SimData.Vehicle v : vs) {
                String root = null;
                for (long st : v.stopStation) {
                    Node station = all.get(SimData.key(v.dim, st));
                    if (station == null || !station.sim) continue;
                    if (root == null) root = station.key();
                    else u.union(root, station.key());
                }
                vehicleRoot.put(v.uuid, root == null ? "vehicle:" + v.uuid : root);
                u.find(root == null ? "vehicle:" + v.uuid : root);
            }
            Map<String, Sim.Group> byRoot = new LinkedHashMap<>();
            for (Node n : sim) {
                Sim.Group g = byRoot.computeIfAbsent(u.find(n.key()), k -> new Sim.Group(all));
                g.add(n);
            }
            for (SimPower.Grid grid : grids) {
                if (grid.mixed()) continue;
                Sim.Group g = byRoot.get(u.find(grid.members().getFirst().key()));
                if (g != null) g.grids.add(grid.members());
            }
            for (Link l : data.stored.links) {
                if (!l.dim.equals(dimEntry.getKey())) continue;
                Node from = all.get(l.from), to = all.get(l.to);
                Node anchor = from != null && from.sim ? from : to != null && to.sim ? to : null;
                if (anchor == null) continue;
                Sim.Group g = byRoot.get(u.find(anchor.key()));
                if (g != null) g.links.add(l);
            }
            for (SimData.Vehicle v : vs) {
                Sim.Group g = byRoot.computeIfAbsent(u.find(vehicleRoot.get(v.uuid)), k -> new Sim.Group(all));
                g.vehicles.add(v);
            }
            groups.addAll(byRoot.values());
        }
        lastVehicles = vehicles;
        return groups;
    }

    /** The power grids of the simulated buildings: worked out again when the wires or the simulated buildings change. */
    private static List<SimPower.Grid> grids(ServerLevel level, SimData data, List<Node> sim) {
        StringBuilder sig = new StringBuilder().append(WireMap.get(level).version()).append(':');
        for (Node n : sim) sig.append(n.pos).append(',');
        // PowerGrids' own grids change as chunks tick or stop: looked at again once a second anyway
        sig.append(':').append(level.getGameTime() / 20);
        String key = sig.toString();
        if (key.equals(GRIDS_FOR.get(level))) return GRIDS.get(level);
        List<SimPower.Grid> grids = SimPower.grids(level, data, sim);
        GRIDS.put(level, grids);
        GRIDS_FOR.put(level, key);
        return grids;
    }

    /**
     * A simulated member of a grid PowerGrids balances: whether that grid gave it power; a generator's fuel and a
     * Power Storage's charge are its remembered stand-in's (PowerMemory), which hands them over when it is back.
     */
    private static void mixedBefore(@Nullable ServerLevel level, Node n) {
        if (level == null) return;
        MIXED.put(n.key(), n.connectors.isEmpty() ? n.pos : n.connectors.getFirst());
        PowerGrids.Grid grid = n.connectors.isEmpty() ? null : PowerGrids.at(level, n.connectors.getFirst());
        GridStats stats = grid == null ? null : grid.stats;
        if (n.su > 0) n.powered = stats != null && stats.powered && !stats.tripped;
        PowerMemory.Remembered r = PowerMemory.get(level).at(level, n.blockPos());
        if (r != null) {
            if (n.power.equals("storage")) n.stored = r.stored();
            if (Sim.holdsFuse(n)) n.tripped = r.tripped();
        }
    }

    /** Union-find over node keys. */
    private static final class Union {
        private final Map<String, String> parent = new HashMap<>();

        String find(String k) {
            String p = parent.get(k);
            if (p == null) {
                parent.put(k, k);
                return k;
            }
            if (p.equals(k)) return k;
            String r = find(p);
            parent.put(k, r);
            return r;
        }

        void union(String a, String b) {
            String ra = find(a), rb = find(b);
            if (!ra.equals(rb)) parent.put(ra, rb);
        }
    }

    // ------------------------------------------------------------------------------------------------ for the tests

    /** The node for a building or port, if the simulation knows it. */
    public static @Nullable Node node(ServerLevel level, BlockPos pos) {
        return SimData.get(level.getServer()).node(level, pos);
    }

    /** Records a building at once (selftests: one put down just before its chunk is let go). */
    public static void recordNow(ServerLevel level, ControllerBlockEntity c) {
        join();
        if (!SimAccess.simulated(c.def())) return;
        record(level, SimData.get(level.getServer()), c);
    }

    /**
     * One step of a separate set of simulated buildings as at game time {@code now}, start to finish on the server
     * thread: the checks each tick would make, the border, the groups and the step itself (the cost selftest).
     */
    static void benchStep(MinecraftServer s, SimData data, long now) {
        int nodes = lastNodes, simulated = lastSimulated;
        for (int t = 0; t < STEP; t++) transitions(s, data, now);
        lastNodes = nodes;
        lastSimulated = simulated;
        boundary(s, data, now);
        for (Sim.Group g : groups(s, data)) Sim.step(g, now);
    }

    /** Runs a step now on the server thread (selftests). */
    public static void stepNow(MinecraftServer s) {
        join();
        SimData data = SimData.get(s);
        long now = s.overworld().getGameTime();
        transitions(s, data, now);
        boundary(s, data, now);
        submit(s, data, now);
        join();
    }

    public static BuildingDef def(Node n) {
        return com.meakaandre.siftec.building.Buildings.def(n.building);
    }
}
