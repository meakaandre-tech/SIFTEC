package com.meakaandre.siftec.sim;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.building.BuildingDef;
import com.meakaandre.siftec.building.Buildings;
import com.meakaandre.siftec.building.ControllerBlockEntity;
import com.meakaandre.siftec.building.PortBlock;
import com.meakaandre.siftec.building.SimAccess;
import com.meakaandre.siftec.drone.DronePortBlockEntity;
import com.meakaandre.siftec.drone.PortContainer;
import com.meakaandre.siftec.power.GridStats;
import com.meakaandre.siftec.power.PowerGrids;
import com.meakaandre.siftec.power.PowerMemory;
import com.meakaandre.siftec.power.WireMap;
import com.meakaandre.siftec.sim.SimData.Link;
import com.meakaandre.siftec.sim.SimData.Node;
import com.meakaandre.siftec.sink.Sink;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Always-running factories (work package 17): every Build Gun building, Drone Port and the inventories belts end in
 * keeps working while its chunks do not tick, without any chunk being loaded for it.
 * <ul>
 * <li>While any chunk a building stands in ticks, it runs for real, and is recorded ({@link #record}): what it makes and
 * at what rate, where its belts and pipes go ({@link Tracer}), its wire connectors; every half second, what its buffers
 * hold.</li>
 * <li>When the last of its chunks stops ticking (unloaded, or loaded but out of simulation distance), its buffers are
 * read there and then, while its block entities are still there ({@link #chunkStopped}), and the simulation takes over
 * from that: groups of linked buildings are stepped together twice a second on a worker thread ({@link Sim}).</li>
 * <li>When one of its chunks ticks again, the catch-up writes what changed into the building in one go, before the
 * building's first real tick ({@link #awake}): its output buffers filled, its inputs and fuel used up, its storage, fuse
 * and counters ({@link Buffers}, {@link SimAccess#handBack}); what the simulation still had on its belts goes on into the
 * building at their end.</li>
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
    private static long runningStarted;
    private static boolean stopping;
    private static @Nullable MinecraftServer server;
    /** Nodes in mixed grids (balanced by PowerGrids), by key, with the connector their grid is found by. */
    private static final Map<String, Long> MIXED = new HashMap<>();
    /** The power grids worked out for the last step, kept while nothing changed. */
    private static final Map<ServerLevel, List<SimPower.Grid>> GRIDS = new IdentityHashMap<>();
    private static final Map<ServerLevel, String> GRIDS_FOR = new IdentityHashMap<>();
    /** Reads that fell due while the worker was busy (a building's or port's turn): done as soon as the step is in. */
    private static final Map<String, Due> DUE = new LinkedHashMap<>();
    /** Node keys by dimension and chunk, for what to read when a chunk stops ticking; rebuilt when nodes change. */
    private static final Map<String, Map<Long, List<String>>> BY_CHUNK = new HashMap<>();
    private static @Nullable SimData byChunkFor;
    private static int byChunkVersion = -1;

    private record Due(ServerLevel level, BlockPos pos, boolean port) {
    }

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
            return String.format(Locale.ROOT, "SIFTEC factories: %d/%d simulated in %d groups, %d vehicles; %.3f ms/tick (server %.3f, worker %.3f a step); %d snapshots, %d catch-ups",
                simulated, nodes, groups, vehicles, mainMsPerTick + workerMsPerTick, mainMsPerTick, workerMsPerStep, snapshots, catchUps);
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
            // every building still running for real is read now and handed to the simulation: if its chunk does not
            // tick when the world is back, the simulation goes on from now, not from whenever it was last read
            parkAll(s);
            stopping = true;
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(s -> {
            join();
            server = null;
            LEVELS.clear();
            MIXED.clear();
            GRIDS.clear();
            GRIDS_FOR.clear();
            DUE.clear();
            BY_CHUNK.clear();
            byChunkFor = null;
            runningGroups = List.of();
            Keys.serverStopped();
            Recipes.forget();
            SimVehicles.clear();
        });
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((s, manager, ok) -> Recipes.forget());
        ServerTickEvents.END_SERVER_TICK.register(Factories::tick);
        // a chunk that stops ticking (a player leaves, logs out, teleports): what stood in it is read while it is still there
        ServerChunkEvents.FULL_CHUNK_STATUS_CHANGE.register((level, chunk, from, to) -> {
            if (from.isOrAfter(FullChunkStatus.BLOCK_TICKING) && !to.isOrAfter(FullChunkStatus.BLOCK_TICKING)) {
                chunkStopped(level, chunk, to.isOrAfter(FullChunkStatus.FULL));
            }
        });
        ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> chunkStopped(level, chunk, false));
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

    // ------------------------------------------------------------------------------------------------ chunks

    /** Every chunk a node stands in (its own, if it has not been recorded with more). */
    static List<Long> chunks(Node n) {
        return n.chunks.isEmpty() ? List.of(ChunkPos.pack(n.blockPos())) : n.chunks;
    }

    /** True if any chunk the node stands in ticks (but {@code except}): the building runs for real. */
    static boolean ticking(ServerLevel level, Node n, long except) {
        for (long c : chunks(n)) {
            if (c == except) continue;
            if (level.hasChunk(ChunkPos.getX(c), ChunkPos.getZ(c)) && level.shouldTickBlocksAt(c)) return true;
        }
        return false;
    }

    /** True if every chunk the node stands in is loaded: only then can the catch-up write into it. */
    static boolean loaded(ServerLevel level, Node n) {
        for (long c : chunks(n)) if (!level.hasChunk(ChunkPos.getX(c), ChunkPos.getZ(c))) return false;
        return true;
    }

    private static String dim(ServerLevel level) {
        return level.dimension().identifier().toString();
    }

    private static Map<Long, List<String>> byChunk(SimData data, String dim) {
        if (byChunkFor != data || byChunkVersion != data.version) {
            BY_CHUNK.clear();
            for (Node n : data.stored.nodes.values()) {
                Map<Long, List<String>> m = BY_CHUNK.computeIfAbsent(n.dim, k -> new HashMap<>());
                for (long c : chunks(n)) m.computeIfAbsent(c, k -> new ArrayList<>(2)).add(n.key());
            }
            byChunkFor = data;
            byChunkVersion = data.version;
        }
        return BY_CHUNK.getOrDefault(dim, Map.of());
    }

    /** Whether the simulation runs a node at all (one with nothing to simulate is left alone while its chunk does not tick). */
    private static boolean simulates(Node n) {
        return !n.mode.equals("none") || n.su > 0 || n.kind.equals("drone_port");
    }

    /**
     * A chunk stopped ticking: each node standing in it whose every chunk has now stopped is read at once, while its block
     * entities are still there (a chunk leaving altogether is read from itself, never through the level, which would load
     * it again), and the simulation takes it over from that read, from now.
     */
    private static void chunkStopped(ServerLevel level, LevelChunk chunk, boolean stillLoaded) {
        MinecraftServer s = server;
        if (s == null || stopping || s != level.getServer()) return;
        SimData data = SimData.get(s);
        long at = chunk.getPos().pack();
        List<String> keys = byChunk(data, dim(level)).get(at);
        if (keys == null) return;
        long now = level.getGameTime();
        Buffers.Reach reach = stillLoaded ? Buffers.level(level) : Buffers.leaving(level, chunk);
        for (String k : keys) {
            Node n = data.node(k);
            if (n == null || n.sim || !simulates(n)) continue;
            if (ticking(level, n, at)) continue;
            // the worker never reads a node it does not simulate, so this is safe while a step runs; a node read here
            // before (it stopped, ticked and stopped again during a slow step) is read again. Only a read that worked
            // parks it: otherwise the simulation checks it again (and forgets it if it is gone) when it takes it over
            if (present(level, reach, n) && refresh(level, reach, n)) {
                n.parkedAt = now;
                data.setDirty();
            } else {
                n.parkedAt = Long.MIN_VALUE;
            }
        }
    }

    /** The server is stopping: every node still running for real is read and handed to the simulation. */
    private static void parkAll(MinecraftServer s) {
        SimData data = SimData.get(s);
        long now = s.overworld().getGameTime();
        for (Node n : data.stored.nodes.values()) {
            if (n.sim || !simulates(n)) continue;
            ServerLevel level = level(s, n.dim);
            if (level == null || !ticking(level, n, Long.MIN_VALUE) || !present(level, Buffers.level(level), n)) continue;
            refresh(level, n);
            n.parkedAt = Long.MIN_VALUE;
            start(level, n, now);
        }
        data.setDirty();
    }

    // ------------------------------------------------------------------------------------------------ each tick

    private static void tick(MinecraftServer s) {
        if (stopping) return;
        long t0 = System.nanoTime();
        long now = s.overworld().getGameTime();
        if (running != null && running.isDone()) join();
        if (running == null) {
            SimData data = SimData.get(s);
            due(s, data);
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

    /** The reads that fell due while the worker was busy, done now that it is not. */
    private static void due(MinecraftServer s, SimData data) {
        if (DUE.isEmpty()) return;
        List<Due> all = new ArrayList<>(DUE.values());
        DUE.clear();
        for (Due d : all) {
            if (d.level.getServer() != s || !d.level.isLoaded(d.pos) || !d.level.shouldTickBlocksAt(d.pos)) continue;
            BlockEntity be = d.level.getBlockEntity(d.pos);
            if (d.port && be instanceof DronePortBlockEntity port) read(d.level, data, port);
            else if (!d.port && be instanceof ControllerBlockEntity c) read(d.level, data, c, false);
        }
    }

    /** Every node whose chunks stopped or started ticking goes over to the simulation, or is caught up. */
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
            boolean ticking = ticking(level, n, Long.MIN_VALUE);
            if (n.sim) {
                if (ticking && !stopping && loaded(level, n)) {
                    if (!present(level, Buffers.level(level), n)) gone.add(n.key());
                    else catchUp(level, data, n, now);
                } else {
                    simulated++;
                }
            } else if (!ticking) {
                if (!simulates(n)) continue;
                if (!toSim(level, data, n, now)) gone.add(n.key());
                else simulated++;
            } else {
                // it ticks again before the simulation took it over: the read made as it stopped is stale now
                n.parkedAt = Long.MIN_VALUE;
                if (now % RECORD == 0 && !present(level, Buffers.level(level), n)) gone.add(n.key());
            }
        }
        for (String k : gone) {
            forget(s, data, k);
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

    /**
     * True if what the node stands for is still there (its chunk is loaded). A building the simulation has not read yet
     * (one a belt runs into, or one it never simulates, as the HUB) is there while any controller is.
     */
    private static boolean present(ServerLevel level, Buffers.Reach reach, Node n) {
        BlockPos pos = n.blockPos();
        if (!reach.has(pos)) return false;
        BlockEntity be = reach.be(pos);
        return switch (n.kind) {
            case "drone_port" -> be instanceof DronePortBlockEntity;
            case "inventory" -> ItemStorage.SIDED.find(level, pos, reach.state(pos), be, null) != null;
            default -> be instanceof ControllerBlockEntity c ? n.building.isEmpty() || c.buildingId.equals(n.building)
                : n.building.isEmpty() && n.mode.equals("none") && be != null;
        };
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
            if (!present(level, Buffers.level(level), n)) return false;
            refresh(level, n);
        }
        start(level, n, from);
        data.setDirty();
        return true;
    }

    /** The simulation takes a node over from what it holds now, as at game time {@code from}. */
    private static void start(ServerLevel level, Node n, long from) {
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
        if (level.isLoaded(n.blockPos()) && level.getBlockEntity(n.blockPos()) instanceof ControllerBlockEntity c) c.simulated = true;
        snapshots++;
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
        catchUps++;
        data.setDirty();
        if (be != null) refresh(level, n);
        // what the simulation still had on belts between this and another building that runs for real now goes on into
        // the building at the belt's end (the real belt carries nothing of it)
        MinecraftServer s = level.getServer();
        for (Link l : new ArrayList<>(data.stored.links)) {
            if (l.transit.isEmpty() || !l.from.equals(n.key()) && !l.to.equals(n.key())) continue;
            Node other = data.node(l.from.equals(n.key()) ? l.to : l.from);
            if (other == null || !other.sim) flush(s, data, l, null);
        }
    }

    /**
     * A building's controller is about to tick: if the simulation was running it, the catch-up is done first, so the real
     * building never works on inputs the simulation already used. False if it must not tick yet (part of it is in a chunk
     * that is not loaded, so it cannot be caught up): the simulation keeps it.
     */
    public static boolean awake(ServerLevel level, ControllerBlockEntity c) {
        long now = level.getGameTime();
        if (c.simTicked == now - 1 && !c.simulated) {
            c.simTicked = now;
            return true;
        }
        if (c.simTicked == now) return !c.simulated;
        if (stopping || !SimAccess.simulated(c.def())) {
            c.simTicked = now;
            c.simulated = false;
            return true;
        }
        SimData data = SimData.get(level.getServer());
        Node n = data.node(level, c.getBlockPos());
        if (n != null && n.sim) join();
        if (n == null || !n.sim) {
            if (n != null) n.parkedAt = Long.MIN_VALUE;
            c.simTicked = now;
            c.simulated = false;
            return true;
        }
        if (!loaded(level, n)) {
            c.simulated = true;
            return false;
        }
        catchUp(level, data, n, now);
        c.simTicked = now;
        c.simulated = false;
        return true;
    }

    /** A Drone Port is about to tick: caught up first if the simulation was running it. */
    public static void awake(ServerLevel level, DronePortBlockEntity port) {
        if (stopping) return;
        SimData data = SimData.get(level.getServer());
        Node n = data.node(level, port.getBlockPos());
        if (n == null || !n.sim) return;
        join();
        if (n.sim) catchUp(level, data, n, level.getGameTime());
    }

    // ------------------------------------------------------------------------------------------------ recording

    /** A building's controller ticked: it is recorded now and then, and its buffers read every half second. */
    public static void building(ServerLevel level, ControllerBlockEntity c) {
        if (stopping || !SimAccess.simulated(c.def())) return;
        long now = level.getGameTime();
        long phase = now + (c.getBlockPos().asLong() & 0x7FFF);
        if (phase % STEP != 0) return;
        if (busy()) {
            // the worker is still on a step: read it as soon as that is in, not at its next turn
            DUE.put(SimData.key(level, c.getBlockPos()), new Due(level, c.getBlockPos().immutable(), false));
            return;
        }
        read(level, SimData.get(level.getServer()), c, phase % RECORD == 0);
    }

    private static void read(ServerLevel level, SimData data, ControllerBlockEntity c, boolean full) {
        Node n = data.node(level, c.getBlockPos());
        if (n != null && n.sim) return;
        if (n == null || n.recordedAt == Long.MIN_VALUE || full) record(level, data, c);
        else refresh(level, n);
    }

    /** Reads a building's model, its buffers and its belts and pipes. */
    static void record(ServerLevel level, SimData data, ControllerBlockEntity c) {
        Node n = data.nodeOrNew(level, c.getBlockPos(), "building");
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
    private static boolean refresh(ServerLevel level, Buffers.Reach reach, Node n) {
        if (!reach.has(n.blockPos())) return false;
        BlockEntity be = reach.be(n.blockPos());
        if (n.kind.equals("drone_port")) {
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
                    to = data.nodeOrNew(level, e.at(), "inventory");
                    to.mode = "store";
                    if (!to.sim) refresh(level, to);
                } else {
                    if (e.node().equals(c.getBlockPos())) continue;
                    to = data.nodeOrNew(level, e.node(), "building");
                    // a building it has not read yet (or never will, as the HUB): known by its id until it is
                    if (to.building.isEmpty() && level.isLoaded(e.node()) && level.getBlockEntity(e.node()) instanceof ControllerBlockEntity tc) to.building = tc.buildingId;
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
                if (back < t.n && !l.fluid) drop(level, n.blockPos(), t.key, t.n - back);
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

    /** A Drone Port ticked (once a second): it is a node, with its flight kept up to date for the map. */
    public static void port(ServerLevel level, DronePortBlockEntity port) {
        if (stopping) return;
        if (busy()) {
            DUE.put(SimData.key(level, port.getBlockPos()), new Due(level, port.getBlockPos().immutable(), true));
            return;
        }
        read(level, SimData.get(level.getServer()), port);
    }

    private static void read(ServerLevel level, SimData data, DronePortBlockEntity port) {
        Node n = data.nodeOrNew(level, port.getBlockPos(), "drone_port");
        if (n.sim) return;
        n.mode = "port";
        n.parkedAt = Long.MIN_VALUE;
        port.toSim(n);
        n.recordedAt = level.getGameTime();
    }

    /**
     * A drone from a ticking port arrives at a port whose chunk does not tick: the packages go into that port's record in
     * the simulation, as far as its arrival slots hold them. Returns how many stacks are left, or -1 if no port is known
     * there. While the worker is on a step nothing is handed over: the drone waits there a second and tries again.
     */
    public static int deliverToPort(ServerLevel level, BlockPos at, List<ItemStack> cargo) {
        SimData data = SimData.get(level.getServer());
        Node far = data.node(level, at);
        if (far == null || !far.kind.equals("drone_port")) return -1;
        if (!far.sim && level.isLoaded(at)) return -1;
        // not simulated yet (its chunk just stopped ticking), or the worker is on a step: the drone waits a second
        if (!far.sim || busy()) return cargo.size();
        if (running != null) join();
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

    /** A building (or Drone Port) was taken down: the simulation forgets it. */
    public static void removed(ServerLevel level, BlockPos controller) {
        MinecraftServer s = level.getServer();
        SimData data = SimData.get(s);
        String key = SimData.key(level, controller);
        if (data.node(key) == null) return;
        join();
        forget(s, data, key);
    }

    /**
     * Forgets a node; what the simulation had on the belts to or from it goes to the building at the other end (or is
     * dropped where the belt was), rather than vanishing with them.
     */
    static void forget(MinecraftServer s, SimData data, String key) {
        for (Link l : new ArrayList<>(data.stored.links)) {
            if (l.from.equals(key) || l.to.equals(key)) flush(s, data, l, key);
        }
        data.remove(key);
    }

    /**
     * Empties a link: what is on it goes into the building at its end, or else back into the one it came from, or else is
     * dropped where it was going (a node that is gone, {@code gone}, takes nothing). Into the world where that building
     * runs for real, into the simulation's record where it does not.
     */
    private static void flush(MinecraftServer s, SimData data, Link l, @Nullable String gone) {
        if (l.transit.isEmpty()) return;
        ServerLevel level = level(s, l.dim);
        Node to = l.to.equals(gone) ? null : data.node(l.to), from = l.from.equals(gone) ? null : data.node(l.from);
        for (SimData.Transit t : l.transit) {
            long left = t.n;
            if (to != null) left -= give(level, to, t.key, left, l.fluid, true);
            if (left > 0 && from != null) left -= give(level, from, t.key, left, l.fluid, false);
            if (left > 0 && !l.fluid && level != null) {
                BlockPos at = BlockPos.of(to == null ? l.fromPort : l.toPort);
                if (level.isLoaded(at)) {
                    drop(level, at, t.key, left);
                    left = 0;
                }
            }
            t.n = left;
        }
        // what had nowhere to go yet stays on the belt (a link that is being forgotten goes with its node)
        l.transit.removeIf(t -> t.n <= 0);
        if (gone != null && !l.transit.isEmpty()) {
            for (SimData.Transit t : l.transit) Siftec.LOGGER.info("SIFTEC: {} {} on a belt between {} and {} had nowhere to go", t.n, t.key, l.from, l.to);
        }
        data.setDirty();
    }

    /** Puts things into a node: at its input ({@code asInput}) or back where it keeps what it made. Returns how many went in. */
    private static long give(@Nullable ServerLevel level, Node n, String key, long count, boolean fluid, boolean asInput) {
        if (n.sim) {
            long take = asInput ? Math.min(count, Sim.room(n, key, fluid)) : count;
            if (take <= 0) return 0;
            if (asInput) Sim.put(n, key, take, fluid);
            else Sim.add(fluid ? n.fout : n.out, key, take);
            return take;
        }
        if (level == null || !loaded(level, n)) return 0;
        return asInput ? Buffers.insert(level, n, key, count, fluid) : Buffers.putBack(level, n, key, count, fluid);
    }

    private static void drop(ServerLevel level, BlockPos at, String key, long count) {
        int max = Math.max(1, Keys.max(key));
        while (count > 0) {
            int c = (int) Math.min(count, max);
            ItemStack stack = Keys.stack(key, c);
            if (stack.isEmpty()) return;
            Containers.dropItemStack(level, at.getX() + 0.5, at.getY() + 1.1, at.getZ() + 0.5, stack);
            count -= c;
        }
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
            if (level == null || !ticking(level, from, Long.MIN_VALUE) || !loaded(level, from)) continue;
            long dt = l.last == Long.MIN_VALUE ? 0 : Math.max(0, now - l.last);
            l.last = now;
            l.allowance = Sim.allow(l, dt);
            long most = Math.min((long) Math.floor(l.allowance + 1e-9), Math.max(0, l.cap - l.carrying()));
            if (most <= 0) continue;
            Map<String, Long> got = Buffers.extract(level, from, most, l.fluid);
            for (Map.Entry<String, Long> e : got.entrySet()) {
                l.transit.add(new SimData.Transit(now + l.delay, e.getKey(), e.getValue()));
                l.allowance -= e.getValue();
            }
        }
    }

    /** After a step: deliveries to ticking buildings, Sink points, drones and trucks at ticking stations, power memory. */
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
                if (level == null || !loaded(level, to)) continue;
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
            for (SimData.Vehicle v : g.vehicles) if (v.dockPending >= 0) dockReal(s, data, v);
            // a generator or Power Storage in a grid the simulation balances by itself: its memory follows the simulation
            for (List<Node> grid : g.grids) for (Node n : grid) syncMemory(s, n, false);
        }
        for (Map.Entry<String, Long> e : MIXED.entrySet()) {
            Node n = data.node(e.getKey());
            ServerLevel level = n == null ? null : level(s, n.dim);
            if (n == null || level == null || !n.sim) continue;
            // a generator or Power Storage in such a grid is its remembered self (PowerGrids burns and charges it; the
            // simulation burns what it gave from the fuel it has, before each step); a consumer asks for what it needs
            if (Sim.generator(n) || n.power.equals("storage")) continue;
            PowerMemory.Remembered r = PowerMemory.get(level).at(level, n.blockPos());
            if (r != null) r.simulateDemand(Sim.demand(n, now));
        }
        data.setDirty();
        mainLastNanos += System.nanoTime() - t0;
    }

    /**
     * Keeps a simulated generator's or Power Storage's power memory and the simulation in step. In a mixed grid (balanced
     * by PowerGrids) what its stand-in gave is burnt from the simulation's fuel, its stand-in is told what that fuel now
     * holds (belts bring more), and its charge and fuse are the grid's; in a grid the simulation balances by itself its
     * memory is told the simulation's fuel, charge and fuse, so either side always starts from the other's last state.
     */
    private static void syncMemory(MinecraftServer s, Node n, boolean mixed) {
        if (!n.sim || !Sim.holdsFuse(n)) return;
        ServerLevel level = level(s, n.dim);
        PowerMemory.Remembered r = level == null ? null : PowerMemory.get(level).at(level, n.blockPos());
        if (r == null) return;
        boolean fuel = switch (n.power) {
            case "biomass", "coal", "fuel", "nuclear" -> true;
            default -> false;
        };
        double spent = r.takeSpent();
        if (spent > 0 && fuel) {
            Sim.burn(n, spent);
            if (n.fullSu > 0) Sim.drink(n, spent / n.fullSu);
        }
        double cap = fuel ? Sim.capacity(n) : -1, reserve = fuel ? Sim.fuelEnergy(n) : -1;
        if (mixed) {
            if (fuel) r.simFuel(cap, reserve);
            if (n.power.equals("storage")) n.stored = r.stored();
            n.tripped = r.tripped();
        } else {
            r.fromSim(cap, reserve, n.stored, n.tripped);
        }
    }

    private static void pay(MinecraftServer s, Node n) {
        long points = 0;
        for (Map.Entry<String, Long> e : n.sunk.entrySet()) {
            Long worth = n.worth.get(e.getKey());
            points += (worth == null ? SimAccess.worth(e.getKey()) : worth) * e.getValue();
        }
        n.sunk.clear();
        if (points > 0 && !n.company.isEmpty()) Sink.paySimulated(s, n.company, points);
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
            ItemStack rest = other.items.add(Keys.stack(c.key, c.count), PortContainer.IN, PortContainer.SIZE);
            if (!rest.isEmpty()) left.add(new SimData.Slot(c.key, rest.getCount()));
        }
        n.cargo = left;
        if (left.isEmpty()) {
            n.state = Sim.HOMEBOUND;
            n.ticks = n.total;
        }
        data.setDirty();
    }

    /**
     * A simulated vehicle stopped at a Truck Station whose chunk ticks (the route point it stands at does not): the station
     * unloads it into its vaults, or loads it from them, and fills its tank, in the world.
     */
    private static void dockReal(MinecraftServer s, SimData data, SimData.Vehicle v) {
        int stop = v.dockPending;
        v.dockPending = -1;
        if (stop < 0 || stop >= v.stopStation.size()) return;
        ServerLevel level = level(s, v.dim);
        Node st = data.node(SimData.key(v.dim, v.stopStation.get(stop)));
        if (level == null || st == null || st.sim || !loaded(level, st)) return;
        long moved = 0;
        if (st.unload) {
            List<SimData.Slot> keep = new ArrayList<>();
            for (SimData.Slot c : v.cargo) {
                if (c.empty()) continue;
                long put = Buffers.insert(level, st, c.key, c.count, false);
                moved += put;
                if (c.count - put > 0) keep.add(new SimData.Slot(c.key, c.count - put));
            }
            v.cargo = keep;
        } else {
            List<SimData.Slot> cargo = new ArrayList<>(v.cargo);
            while (cargo.size() < v.slots) cargo.add(new SimData.Slot());
            long room = 0;
            for (SimData.Slot c : cargo) room += c.empty() ? 64 : Math.max(0, Keys.max(c.key) - c.count);
            Map<String, Long> got = Buffers.extract(level, st, room, false);
            for (Map.Entry<String, Long> e : got.entrySet()) {
                long rest = Sim.addSlots(cargo, 0, v.slots, e.getKey(), e.getValue());
                moved += e.getValue() - rest;
                // what does not fit after all (a smaller stack size) goes back
                if (rest > 0) Buffers.putBack(level, st, e.getKey(), rest, false);
            }
            cargo.removeIf(SimData.Slot::empty);
            v.cargo = cargo;
        }
        // the station's fuel tank fills the vehicle's
        for (String fuel : st.fuelMj.keySet()) {
            Double mj = st.fuelMj.get(fuel);
            if (mj == null || mj <= 0) continue;
            long want = (long) Math.floor((v.tank - v.fuel) / mj);
            if (want <= 0) break;
            Map<String, Long> took = Buffers.extract(level, st, want, true);
            for (Map.Entry<String, Long> e : took.entrySet()) {
                Double per = st.fuelMj.get(e.getKey());
                if (per != null && per > 0) v.fuel = Math.min(v.tank, v.fuel + e.getValue() * per);
                else Buffers.putBack(level, st, e.getKey(), e.getValue(), true);
            }
        }
        v.itemsMoved += moved;
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
        boolean real = data == SimData.get(s);
        if (real) MIXED.clear();
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
                    for (Node n : g.members()) mixedBefore(level, n, real);
                } else {
                    for (Node n : g.members()) n.mixed = false;
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
     * A simulated member of a grid PowerGrids balances: whether that grid gave it power; a generator's fuel and a Power
     * Storage's charge and fuse are kept in step with its remembered stand-in ({@link #syncMemory}).
     */
    private static void mixedBefore(@Nullable ServerLevel level, Node n, boolean real) {
        if (level == null) return;
        if (real) MIXED.put(n.key(), n.connectors.isEmpty() ? n.pos : n.connectors.getFirst());
        PowerGrids.Grid grid = n.connectors.isEmpty() ? null : PowerGrids.at(level, n.connectors.getFirst());
        GridStats stats = grid == null ? null : grid.stats;
        if (n.su > 0) n.powered = stats != null && stats.powered && !stats.tripped;
        n.mixed = true;
        if (real) syncMemory(level.getServer(), n, true);
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

    /**
     * Keeps the worker busy, as a step that takes long would, until {@code release} counts down or {@code maxMillis} pass
     * (selftests of a lagging server).
     */
    public static void holdWorker(CountDownLatch release, long maxMillis) {
        join();
        runningGroups = List.of();
        running = WORKER.submit(() -> {
            try {
                release.await(maxMillis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    public static BuildingDef def(Node n) {
        return Buildings.def(n.building);
    }
}
