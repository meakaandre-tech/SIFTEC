package com.meakaandre.siftec.sim;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.building.Logic;
import com.meakaandre.siftec.building.PowerRole;
import com.meakaandre.siftec.save.JsonSavedData;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the always-running factories need while their chunks do not tick, kept with the world:
 * <ul>
 * <li>{@link Node}: each Build Gun building, Drone Port and plain inventory a belt ends in, as the background
 * simulation knows it: what it makes and at what rate, what its buffers hold, its power;</li>
 * <li>{@link Link}: a belt or pipe from one node's output to another's input, with what is on its way along it;</li>
 * <li>{@link Vehicle}: a Truck, Tractor or Explorer driving its route by time while the land it is on does not tick.</li>
 * </ul>
 * Plain Java objects, so new fields never need a migration ({@link JsonSavedData}). Read and changed only on the server
 * thread, except while a step runs on the simulation's worker ({@link Factories}), which the save waits for.
 */
public class SimData extends JsonSavedData<SimData.Stored> {
    public static class Stored {
        /** By {@link #key}. */
        public Map<String, Node> nodes = new LinkedHashMap<>();
        public List<Link> links = new ArrayList<>();
        public List<Vehicle> vehicles = new ArrayList<>();
    }

    /** A building (or port, or inventory) as the simulation knows it. */
    public static class Node {
        public String dim = "";
        public long pos;
        /** A building, an inventory (a chest or vault a belt ends in) or a Drone Port. */
        public NodeKind kind = NodeKind.BUILDING;
        public String building = "";
        public Logic logic = Logic.NONE;
        public String company = "";
        /** True while its chunk does not tick and the simulation runs it. */
        public boolean sim;
        /** The game time up to which it has been simulated (or was snapshotted at). */
        public long simTime;
        /** The game time its model (recipes, links) was last read from the world. */
        public long recordedAt = Long.MIN_VALUE;
        /** Every chunk it stands in (as packed chunk positions): it runs for real while any of them ticks. */
        public List<Long> chunks = new ArrayList<>();
        /**
         * The game time its buffers were read as its last chunk stopped ticking, when the simulation takes it over later
         * (the worker was busy): the simulation starts from that read, at that time. MIN_VALUE: none.
         */
        public long parkedAt = Long.MIN_VALUE;

        // ---- the model, read while it is loaded (SimRecorder)
        /** Craft, smelt, mine, extract, gen, battery, sink, store, port, or none (nothing to simulate). */
        public NodeMode mode = NodeMode.NONE;
        public List<Recipe> recipes = new ArrayList<>();
        /** The items of each tag its recipes name ("#tag" to item keys). */
        public Map<String, List<String>> alts = new HashMap<>();
        /** SU it draws while it works (0: needs no power). */
        public double su;
        /** Its power role (BuildingDef.power). */
        public PowerRole power = PowerRole.NONE;
        /** A generator's or augmenter's SU at full. */
        public double fullSu;
        /** What its buffers hold at most, in items (and mB). */
        public long inCap, outCap, fluidInCap, fluidOutCap;
        /** Its wire connectors. */
        public List<Long> connectors = new ArrayList<>();
        // mining and extracting
        public String mineItem = "";
        public long minePeriod;
        public double minePer = 1;
        public String fluidOut = "";
        /** mB a tick an extractor makes. */
        public double fluidRate;
        /** A generator's water: mB a tick at full power (the Coal Generator's and the Nuclear Power Plant's), and the part of an mB owed. */
        public double water, waterFrac;
        // generators
        /** Fuel by key (an item key, or "fluid:<id>"): megajoules per item, or per mB. */
        public Map<String, Double> fuelMj = new HashMap<>();
        /** Nuclear: what a spent rod leaves (key) and how many. */
        public Map<String, String> waste = new HashMap<>();
        public Map<String, Integer> wasteCount = new HashMap<>();
        /** Sink: points by item key (looked up on the server thread). */
        public Map<String, Long> worth = new HashMap<>();

        // ---- the state, while simulated
        public Map<String, Long> in = new HashMap<>(), out = new HashMap<>(), fin = new HashMap<>(), fout = new HashMap<>();
        /** What the buffers held when it was snapshotted: the catch-up writes back only what changed since. */
        public Map<String, Long> baseIn = new HashMap<>(), baseOut = new HashMap<>(), baseFin = new HashMap<>(), baseFout = new HashMap<>();
        /** A recipe under way: ends at busyUntil; its outputs appear at pendingAt. */
        public long busyUntil = Long.MIN_VALUE, pendingAt = Long.MIN_VALUE;
        public Map<String, Long> pending = new HashMap<>(), pendingFluid = new HashMap<>();
        /** A miner's next break, and the part of an item owed (Fortune's average, an extractor's mB). */
        public long nextBreak = Long.MIN_VALUE;
        public double frac;
        /** A generator's banked energy in its machines (SU-ticks), and what it held when snapshotted. */
        public double energy, energy0;
        /** A Power Storage's charge (SU-ticks) and its fuse. */
        public double stored;
        public boolean tripped;
        /** Whether it had power over the last step. */
        public boolean powered = true;
        /** Whether its grid was balanced by PowerGrids (part of it ticks) at the last step, rather than by the simulation. */
        public boolean mixed;
        /** An augmenter: ticks its last matrix still feeds it. */
        public long fed, fed0;
        /** What the simulation did since the snapshot: recipes started (or breaks), items mined, SU given. */
        public long made, mined;
        public double given;
        /** Items a Sink took, by key, not yet paid for (paid on the server thread). */
        public Map<String, Long> sunk = new HashMap<>();
        /** The SU it asked for over the last step, and gave (generators), for the mixed grids and the status. */
        public double demandNow, capacityNow;
        /** The fuel it burnt last (a generator's leftover energy goes back as that fuel). */
        public String lastFuel = "";

        // ---- a Drone Port
        public List<Slot> slots = new ArrayList<>();
        /** Its slots when it was snapshotted: the catch-up writes back only what changed since. */
        public List<Slot> baseSlots = new ArrayList<>();
        public List<Slot> cargo = new ArrayList<>();
        public int state, ticks, total;
        public @Nullable Long destination;
        public boolean hasDrone;
        /** A drone arriving at a port whose chunk ticks: handed over on the server thread. */
        public boolean handOver;
        /** Fire charges a round trip takes, and the ticks each way (worked out from the distance when recorded). */
        public int charges = 1, flight;
        /** A Truck Station: unloading vehicles into its vaults (true) or loading them (false). */
        public boolean unload;

        public String key() {
            return dim + "|" + pos;
        }

        public BlockPos blockPos() {
            return BlockPos.of(pos);
        }
    }

    /** One recipe a node runs: items and fluids in and out, the ticks it takes, when its outputs appear. */
    public static class Recipe {
        public Map<String, Long> in = new LinkedHashMap<>(), out = new LinkedHashMap<>();
        public Map<String, Long> fin = new LinkedHashMap<>(), fout = new LinkedHashMap<>();
        public long ticks = 20, outDelay = 20;
        /** The Smelter's: a batch of up to this many items of its one input at once (1 for the rest). */
        public int batch = 1;
        public long batchExtra;
        public String id = "";
    }

    /** A slot of a Drone Port or a vehicle: an item key and how many. */
    public static class Slot {
        public String key = "";
        public long count;

        public Slot() {
        }

        public Slot(String key, long count) {
            this.key = key;
            this.count = count;
        }

        public boolean empty() {
            return key.isEmpty() || count <= 0;
        }
    }

    /** A belt or pipe from one node's output port to another node's input. */
    public static class Link {
        public String dim = "";
        public String from = "", to = "";
        /** The output port it leaves from, and the input port (or inventory) it ends at. */
        public long fromPort, toPort;
        public boolean fluid;
        /** Items (or mB) a tick it carries at most, and the ticks a thing takes along it. */
        public double rate = 1;
        public long delay;
        /** How many things fit on it at once. */
        public long cap = 8;
        /** Its share of a split output (1 when nothing splits it). */
        public double share = 1;
        public double allowance;
        public List<Transit> transit = new ArrayList<>();
        public long recordedAt;
        /** The game time it last took from its source. */
        public long last = Long.MIN_VALUE;

        public long carrying() {
            long n = 0;
            for (Transit t : transit) n += t.n;
            return n;
        }
    }

    /** Things on their way along a link: they arrive at {@code at}. */
    public static class Transit {
        public long at;
        public String key = "";
        public long n;

        public Transit() {
        }

        public Transit(long at, String key, long n) {
            this.at = at;
            this.key = key;
            this.n = n;
        }
    }

    /** A vehicle driving its route by time while the land it is on does not tick. */
    public static class Vehicle {
        public String uuid = "";
        public String type = "";
        public String dim = "";
        public String company = "";
        public double fuel;
        /** Its kind's figures: blocks a tick at full speed, MW it burns while driving, storage slots, tank MJ. */
        public double speed = 0.4, mw = 75, tank = 75_000;
        public int slots = 54;
        public List<Slot> cargo = new ArrayList<>();
        public List<Slot> refund = new ArrayList<>();
        /** The route, three numbers a point; its stops (route point, Truck Station) as the entity has them. */
        public List<Double> route = new ArrayList<>();
        public List<Integer> stopAt = new ArrayList<>();
        public List<Long> stopStation = new ArrayList<>();
        /** Ticks each leg took when it last drove it for real (to the point of the same index), 0 if never. */
        public List<Integer> legTicks = new ArrayList<>();
        public int target;
        public @Nullable Long lastStation;
        public int stopsMade;
        public long itemsMoved;
        /** Ticks into the leg to the target, and into its stop there (-1: not stopped). */
        public double leg;
        public int docked = -1;
        public float yaw;
        public double x, y, z;
        public long simTime;
        /** Its fuel ran out on the way: it waits there. */
        public boolean dry;
        /** A stop (its index) at a Truck Station whose chunk ticks: the server thread loads or unloads it there. -1: none. */
        public int dockPending = -1;
        /** Numbers in {@link #route} a point takes (x, y, z). */
        static final int POINT_SIZE = 3;

        public int points() {
            return route.size() / POINT_SIZE;
        }

        public double px(int i) {
            return route.get(i * POINT_SIZE);
        }

        public double py(int i) {
            return route.get(i * POINT_SIZE + 1);
        }

        public double pz(int i) {
            return route.get(i * POINT_SIZE + 2);
        }
    }

    // ------------------------------------------------------------------------------------------------ saving

    private static final Codec<SimData> BASE = JsonSavedData.codec(SimData::new);
    /** The save waits for a step the worker may be running, so it never reads a half-changed state. */
    static final Codec<SimData> CODEC = new Codec<>() {
        @Override
        public <T> DataResult<Pair<SimData, T>> decode(DynamicOps<T> ops, T input) {
            return BASE.decode(ops, input);
        }

        @Override
        public <T> DataResult<T> encode(SimData data, DynamicOps<T> ops, T prefix) {
            Factories.join();
            encodes++;
            return BASE.encode(data, ops, prefix);
        }
    };
    /** How many times it has been written (selftests: which saves write it). */
    private static int encodes;

    public static int encodes() {
        return encodes;
    }

    static final SavedDataType<SimData> TYPE = new SavedDataType<>(Siftec.id("factories"), SimData::new, CODEC, null);

    /** Bumped whenever nodes come or go or change their chunks, for the index of nodes by chunk ({@link Factories}). */
    transient int version;
    /** True while a quick save runs (a company's points): the simulation is written with the world save only. */
    private static boolean quick;

    public SimData() {
        super("factories", Stored.class, Stored::new);
    }

    /**
     * Runs a quick save of other data (a company's points, written within seconds) without this: written out of step with
     * the chunks, a crash would lose or repeat a catch-up, and writing it waits for the worker.
     */
    public static void quickSave(Runnable save) {
        quick = true;
        try {
            save.run();
        } finally {
            quick = false;
        }
    }

    @Override
    public boolean isDirty() {
        return !quick && super.isDirty();
    }

    public static SimData get(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(TYPE);
    }

    @Override
    protected void loaded() {
        // fields missing from an older file come back as null from Gson
        for (Node n : stored.nodes.values()) fix(n);
        for (Link l : stored.links) if (l.transit == null) l.transit = new ArrayList<>();
        stored.links.removeIf(l -> l.from == null || l.to == null);
        for (Vehicle v : stored.vehicles) {
            if (v.cargo == null) v.cargo = new ArrayList<>();
            if (v.refund == null) v.refund = new ArrayList<>();
            if (v.legTicks == null) v.legTicks = new ArrayList<>();
        }
    }

    private static void fix(Node n) {
        if (n.recipes == null) n.recipes = new ArrayList<>();
        if (n.connectors == null) n.connectors = new ArrayList<>();
        if (n.alts == null) n.alts = new HashMap<>();
        if (n.fuelMj == null) n.fuelMj = new HashMap<>();
        if (n.waste == null) n.waste = new HashMap<>();
        if (n.wasteCount == null) n.wasteCount = new HashMap<>();
        if (n.worth == null) n.worth = new HashMap<>();
        if (n.in == null) n.in = new HashMap<>();
        if (n.out == null) n.out = new HashMap<>();
        if (n.fin == null) n.fin = new HashMap<>();
        if (n.fout == null) n.fout = new HashMap<>();
        if (n.baseIn == null) n.baseIn = new HashMap<>();
        if (n.baseOut == null) n.baseOut = new HashMap<>();
        if (n.baseFin == null) n.baseFin = new HashMap<>();
        if (n.baseFout == null) n.baseFout = new HashMap<>();
        if (n.pending == null) n.pending = new HashMap<>();
        if (n.pendingFluid == null) n.pendingFluid = new HashMap<>();
        if (n.sunk == null) n.sunk = new HashMap<>();
        if (n.slots == null) n.slots = new ArrayList<>();
        if (n.cargo == null) n.cargo = new ArrayList<>();
        if (n.baseSlots == null) n.baseSlots = new ArrayList<>();
        if (n.chunks == null) n.chunks = new ArrayList<>();
        if (n.lastFuel == null) n.lastFuel = "";
        // an id this version does not know comes back from Gson as null
        if (n.kind == null) n.kind = NodeKind.BUILDING;
        if (n.logic == null) n.logic = Logic.NONE;
        if (n.mode == null) n.mode = NodeMode.NONE;
        if (n.power == null) n.power = PowerRole.NONE;
        for (Recipe r : n.recipes) {
            if (r.in == null) r.in = new LinkedHashMap<>();
            if (r.out == null) r.out = new LinkedHashMap<>();
            if (r.fin == null) r.fin = new LinkedHashMap<>();
            if (r.fout == null) r.fout = new LinkedHashMap<>();
        }
    }

    // ------------------------------------------------------------------------------------------------ access

    public static String key(Level level, BlockPos pos) {
        return key(level.dimension().identifier().toString(), pos.asLong());
    }

    public static String key(String dim, long pos) {
        return dim + "|" + pos;
    }

    public @Nullable Node node(String key) {
        return stored.nodes.get(key);
    }

    public @Nullable Node node(Level level, BlockPos pos) {
        return stored.nodes.get(key(level, pos));
    }

    /** The node at a spot, made (empty, not simulated) if there is none. */
    public Node nodeOrNew(Level level, BlockPos pos, NodeKind kind) {
        String k = key(level, pos);
        Node n = stored.nodes.get(k);
        if (n == null) {
            n = new Node();
            n.dim = level.dimension().identifier().toString();
            n.pos = pos.asLong();
            n.kind = kind;
            fix(n);
            stored.nodes.put(k, n);
            version++;
            setDirty();
        }
        return n;
    }

    /** Forgets a node and every link to or from it (the building is gone; {@link FactoryBorder#forget} first puts what is on those links somewhere). */
    public void remove(String key) {
        if (stored.nodes.remove(key) == null) return;
        version++;
        stored.links.removeIf(l -> l.from.equals(key) || l.to.equals(key));
        setDirty();
    }

    public @Nullable Vehicle vehicle(String uuid) {
        for (Vehicle v : stored.vehicles) if (v.uuid.equals(uuid)) return v;
        return null;
    }

    static void ensure(Node n) {
        fix(n);
    }
}
