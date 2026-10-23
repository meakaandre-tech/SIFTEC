package com.meakaandre.siftec.sim;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.building.BuildingDef;
import com.meakaandre.siftec.building.Buildings;
import com.meakaandre.siftec.building.ControllerBlockEntity;
import com.meakaandre.siftec.building.SimAccess;
import com.meakaandre.siftec.drone.DronePortBlockEntity;
import com.meakaandre.siftec.sim.SimData.Node;
import com.meakaandre.siftec.util.Chunks;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Always-running factories: every Build Gun building, Drone Port and the inventories belts end in
 * keeps working while its chunks do not tick, without any chunk being loaded for it.
 * <ul>
 * <li>While any chunk a building stands in ticks, it runs for real, and is recorded ({@link FactoryRecording#record}):
 * what it makes and at what rate, where its belts and pipes go ({@link Tracer}), its wire connectors; every half second,
 * what its buffers hold.</li>
 * <li>When the last of its chunks stops ticking (unloaded, or loaded but out of simulation distance), its buffers are
 * read there and then, while its block entities are still there ({@link FactoryChunks#chunkStopped}), and the
 * simulation takes over from that: groups of linked buildings are stepped together twice a second on a worker thread
 * ({@link Sim}).</li>
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
    /** Weights of the running averages of the time taken: what the average keeps, and what the newest step adds. */
    private static final double MAIN_AVG_KEEP = 0.98, MAIN_AVG_NEW = 0.02, WORKER_AVG_KEEP = 0.9, WORKER_AVG_NEW = 0.1;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "SIFTEC factory simulation");
        t.setDaemon(true);
        return t;
    });
    private static @Nullable Future<?> running;
    static List<Sim.Group> runningGroups = List.of();
    static boolean stopping;
    static @Nullable MinecraftServer server;
    /** Reads that fell due while the worker was busy (a building's or port's turn): done as soon as the step is in. */
    private static final Map<String, Due> DUE = new LinkedHashMap<>();

    private record Due(ServerLevel level, BlockPos pos, boolean port) {
    }

    // ---- what the debug overlay shows
    private static double mainAvgNanos, workerAvgNanos;
    static long mainLastNanos, workerLastNanos;
    static int lastNodes, lastSimulated, lastGroups, lastVehicles;
    static long catchUps, snapshots;

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
            FactoryChunks.parkAll(s);
            stopping = true;
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(s -> {
            join();
            server = null;
            FactoryChunks.LEVELS.clear();
            FactoryGroups.MIXED.clear();
            FactoryGroups.GRIDS.clear();
            FactoryGroups.GRIDS_FOR.clear();
            DUE.clear();
            FactoryChunks.BY_CHUNK.clear();
            FactoryChunks.byChunkFor = null;
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
                FactoryChunks.chunkStopped(level, chunk, to.isOrAfter(FullChunkStatus.FULL));
            }
        });
        ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> FactoryChunks.chunkStopped(level, chunk, false));
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
        } catch (ExecutionException e) {
            Siftec.LOGGER.error("SIFTEC: a factory simulation step failed", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Siftec.LOGGER.error("SIFTEC: interrupted while waiting for a factory simulation step", e);
        }
        if (running == f) {
            running = null;
            MinecraftServer s = server;
            if (s != null) FactoryResults.applyResults(s);
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
            due(s, data);
            FactoryCatchUp.transitions(s, data, now);
            if (now % STEP == 0) {
                FactoryBorder.boundary(s, data, now);
                submit(s, data, now);
            }
        }
        SimVehicles.tick(s, now, running == null);
        long took = System.nanoTime() - t0;
        mainLastNanos = took;
        mainAvgNanos = mainAvgNanos * MAIN_AVG_KEEP + took * MAIN_AVG_NEW;
        if (now % 20 == 0) SimStatsPayload.send(s, stats());
    }

    /** The reads that fell due while the worker was busy, done now that it is not. */
    private static void due(MinecraftServer s, SimData data) {
        if (DUE.isEmpty()) return;
        List<Due> all = new ArrayList<>(DUE.values());
        DUE.clear();
        for (Due d : all) {
            if (d.level.getServer() != s || !Chunks.ticking(d.level, d.pos)) continue;
            BlockEntity be = d.level.getBlockEntity(d.pos);
            if (d.port && be instanceof DronePortBlockEntity port) FactoryRecording.read(d.level, data, port);
            else if (!d.port && be instanceof ControllerBlockEntity c) FactoryRecording.read(d.level, data, c, false);
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
        if (!FactoryChunks.loaded(level, n)) {
            c.simulated = true;
            return false;
        }
        FactoryCatchUp.catchUp(level, data, n, now);
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
        if (n.sim) FactoryCatchUp.catchUp(level, data, n, level.getGameTime());
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
        FactoryRecording.read(level, SimData.get(level.getServer()), c, phase % RECORD == 0);
    }

    /** A Drone Port ticked (once a second): it is a node, with its flight kept up to date for the map. */
    public static void port(ServerLevel level, DronePortBlockEntity port) {
        if (stopping) return;
        if (busy()) {
            DUE.put(SimData.key(level, port.getBlockPos()), new Due(level, port.getBlockPos().immutable(), true));
            return;
        }
        FactoryRecording.read(level, SimData.get(level.getServer()), port);
    }

    /**
     * A drone from a ticking port arrives at a port whose chunk does not tick: the packages go into that port's record in
     * the simulation, as far as its arrival slots hold them. Returns how many stacks are left, or -1 if no port is known
     * there. While the worker is on a step nothing is handed over: the drone waits there a second and tries again.
     */
    public static int deliverToPort(ServerLevel level, BlockPos at, List<ItemStack> cargo) {
        SimData data = SimData.get(level.getServer());
        Node far = data.node(level, at);
        if (far == null || far.kind != NodeKind.DRONE_PORT) return -1;
        if (!far.sim && level.isLoaded(at)) return -1;
        // not simulated yet (its chunk just stopped ticking), or the worker is on a step: the drone waits a second
        if (!far.sim || busy()) return cargo.size();
        if (running != null) join();
        Sim.pad(far.slots, SimDrones.PORT_SIZE);
        for (int i = 0; i < cargo.size(); i++) {
            ItemStack s = cargo.get(i);
            if (s.isEmpty()) continue;
            long left = Sim.addSlots(far.slots, SimDrones.PORT_IN, SimDrones.PORT_SIZE, Keys.of(s), s.getCount());
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
        FactoryBorder.forget(s, data, key);
    }

    // ------------------------------------------------------------------------------------------------ the step

    /** Works out the groups of simulated buildings and hands the step to the worker. */
    private static void submit(MinecraftServer s, SimData data, long now) {
        List<Sim.Group> groups = FactoryGroups.groups(s, data);
        lastGroups = groups.size();
        if (groups.isEmpty()) return;
        runningGroups = groups;
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
            workerAvgNanos = workerAvgNanos == 0 ? took : workerAvgNanos * WORKER_AVG_KEEP + took * WORKER_AVG_NEW;
        });
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
        FactoryRecording.record(level, SimData.get(level.getServer()), c);
    }

    /**
     * One step of a separate set of simulated buildings as at game time {@code now}, start to finish on the server
     * thread: the checks each tick would make, the border, the groups and the step itself (the cost selftest).
     */
    static void benchStep(MinecraftServer s, SimData data, long now) {
        int nodes = lastNodes, simulated = lastSimulated;
        for (int t = 0; t < STEP; t++) FactoryCatchUp.transitions(s, data, now);
        lastNodes = nodes;
        lastSimulated = simulated;
        FactoryBorder.boundary(s, data, now);
        for (Sim.Group g : FactoryGroups.groups(s, data)) Sim.step(g, now);
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