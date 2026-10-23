package com.meakaandre.siftec.node;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Where every node is. Nothing is stored: a node's position, type and purity are worked out from the world
 * seed and the cell it is in, so the scanner can find nodes in chunks that have never been generated.
 *
 * The world is cut into cells of {@link #CELL} blocks. In the Overworld about 60% of the cells that have land
 * hold a surface node, always on land: if the cell's first spot is over void (a world of floating islands) a
 * spot on the cell's land is taken instead, and a cell with no land at all holds none. A small island that
 * fits in one cell, with no land in the four cells around it, always gets a node. A cell with a sulfur cave
 * pocket under its land also holds a sulfur node inside that pocket. In the Nether about 60% of cells hold a
 * quartz node. Biomes and ground heights are read from the world generator, which needs no generated chunks.
 *
 * Working a cell out costs some biome and noise samples, so cells are cached, and long searches (the scanners,
 * the Radar Tower) run on a worker thread that only touches the generator, then answer on the server thread.
 */
public final class NodeMap {
    public static final int CELL = 128;
    /** Nodes keep this far from the cell edge, so a whole mound always fits inside the cell. */
    static final int MARGIN = 16;
    /** The starter iron, copper and stone nodes are within this many blocks of spawn (the design asks for 256). */
    public static final int STARTER = 240;
    /** A search for one resource gives up after this long, keeping the best it found. */
    public static final long SEARCH_NANOS = 4_000_000_000L;

    private static final Map<ResourceKey<Level>, Map<Long, List<Node>>> CACHE = new ConcurrentHashMap<>();
    private static final Map<Long, Optional<Node>> SULFUR = new ConcurrentHashMap<>();
    /** Which of a cell's 5 x 5 sample points are over land (by biome); bit 25 marks "worked out". */
    static final Map<Long, Integer> LAND = new ConcurrentHashMap<>();
    /** Ground height of surface nodes as the generator gives it, for waypoints and teleports. */
    static final Map<Long, Integer> GROUND = new ConcurrentHashMap<>();
    /** Height of the pad of surface nodes (see {@link #padHeight}). */
    static final Map<Long, Integer> PAD = new ConcurrentHashMap<>();
    private static volatile long cacheSeed = Long.MIN_VALUE;
    /** The point the map is measured from (world spawn when the world was new); null until settled. */
    static volatile int @Nullable [] origin;
    /** Nodes left out for having nowhere to stand (NodeSavedData's live set): never offered by a search or the map. */
    private static volatile Set<Long> unplaceable = Set.of();
    /** Biome samples taken, for the selftest. */
    public static final AtomicLong SAMPLES = new AtomicLong();

    private NodeMap() {
    }

    public static boolean hasNodes(Level level) {
        return level.dimension() == Level.OVERWORLD || level.dimension() == Level.NETHER;
    }

    /**
     * False for the first moments of a new world, until its spawn point is settled and recorded. Nothing
     * may be looked up before that, or the map would be measured from the wrong place.
     */
    public static boolean ready(ServerLevel level) {
        return origin != null;
    }

    /** The point the map is measured from, {x, z}, or null before it is settled. Safe from any thread. */
    public static int @Nullable [] origin() {
        return origin;
    }

    /** Called every tick (cheap once done): fixes the point the map is measured from. */
    public static void settle(ServerLevel level) {
        if (origin != null) return;
        NodeSavedData data = NodeSavedData.get(level.getServer());
        if (!data.hasOrigin()) {
            var spawn = level.getServer().overworld().getRespawnData().pos();
            data.setOrigin(spawn.getX(), spawn.getZ());
        }
        clearCaches();
        unplaceable = data.unplaceable();
        origin = new int[]{data.originX(), data.originZ()};
    }

    /** True for a node that was left out (a cave or Nether node with nowhere to stand): nothing should point at it. */
    public static boolean placeless(Node node) {
        return unplaceable.contains(node.key());
    }

    /** Cells kept worked out at most (per kind); past this every cache is emptied and cells are worked out again as asked. */
    private static final int CELLS_KEPT = 32_768;

    /** Keeps the caches bounded: a long-running server that has seen much of the world starts them afresh. */
    private static void trimCaches() {
        int cells = 0;
        for (Map<Long, List<Node>> m : CACHE.values()) cells += m.size();
        if (cells > CELLS_KEPT || SULFUR.size() > CELLS_KEPT || LAND.size() > 4 * CELLS_KEPT || GROUND.size() > CELLS_KEPT || PAD.size() > CELLS_KEPT) {
            CACHE.clear();
            SULFUR.clear();
            LAND.clear();
            GROUND.clear();
            PAD.clear();
        }
    }

    private static void clearCaches() {
        NodeStarters.starters = null;
        CACHE.clear();
        SULFUR.clear();
        LAND.clear();
        GROUND.clear();
        PAD.clear();
    }

    /** Forgets every worked-out cell (the selftest uses it to time a first scan). */
    public static void resetCaches() {
        clearCaches();
    }

    /** Forgets everything; called when the server stops, so the next world starts clean. */
    public static void clear() {
        clearCaches();
        origin = null;
        unplaceable = Set.of();
        cacheSeed = Long.MIN_VALUE;
    }

    private static void checkSeed(ServerLevel level) {
        long seed = level.getSeed();
        if (seed != cacheSeed) {
            clearCaches();
            cacheSeed = seed;
        }
    }

    static long cellKey(int cellX, int cellZ) {
        return ((long) cellX << 32) ^ (cellZ & 0xffffffffL);
    }

    /** True if the cell's nodes are already worked out, so asking for them costs nothing. */
    public static boolean cached(ServerLevel level, int cellX, int cellZ) {
        if (!hasNodes(level) || origin == null) return true;
        Map<Long, List<Node>> cells = CACHE.get(level.dimension());
        long key = cellKey(cellX, cellZ);
        return cells != null && cells.containsKey(key) && (level.dimension() != Level.OVERWORLD || SULFUR.containsKey(key));
    }

    /** Works the cell out on the worker thread, so a later {@link #inCell} finds it cached. */
    public static void prefetch(ServerLevel level, int cellX, int cellZ) {
        NodeWorker.prefetch(level, cellX, cellZ);
    }

    /** The surface (or Nether) node of a cell, if any. */
    static List<Node> surface(ServerLevel level, int cellX, int cellZ) {
        checkSeed(level);
        int[] from = origin;
        if (from == null) return List.of();
        Map<Long, List<Node>> cells = CACHE.computeIfAbsent(level.dimension(), k -> new ConcurrentHashMap<>());
        long key = cellKey(cellX, cellZ);
        List<Node> cached = cells.get(key);
        if (cached == null) {
            trimCaches();
            cells = CACHE.computeIfAbsent(level.dimension(), k -> new ConcurrentHashMap<>());
            cached = level.dimension() == Level.NETHER ? NodeCells.nether(level, cellX, cellZ, from) : NodeCells.overworld(level, cellX, cellZ, from);
            cells.put(key, cached);
        }
        return cached;
    }

    static Optional<Node> sulfurIn(ServerLevel level, int cellX, int cellZ) {
        if (level.dimension() != Level.OVERWORLD) return Optional.empty();
        checkSeed(level);
        int[] from = origin;
        if (from == null) return Optional.empty();
        long key = cellKey(cellX, cellZ);
        Optional<Node> cached = SULFUR.get(key);
        if (cached == null) {
            trimCaches();
            cached = Optional.ofNullable(NodeCaves.sulfur(level, cellX, cellZ, from));
            SULFUR.put(key, cached);
        }
        return cached;
    }

    /** The nodes in a cell: none, one, or (with a sulfur cave below) two. */
    public static List<Node> inCell(ServerLevel level, int cellX, int cellZ) {
        if (!hasNodes(level) || origin == null) return List.of();
        List<Node> top = surface(level, cellX, cellZ);
        Optional<Node> cave = sulfurIn(level, cellX, cellZ);
        if (cave.isEmpty()) return top;
        List<Node> both = new ArrayList<>(top);
        both.add(cave.get());
        return both;
    }

    /** The node of this type (any type when null) whose centre is within {@code range} blocks of the column. */
    public static Optional<Node> near(ServerLevel level, int x, int z, int range, NodeType type) {
        for (Node node : inCell(level, Math.floorDiv(x, CELL), Math.floorDiv(z, CELL))) {
            if ((type == null || node.type() == type) && !placeless(node) && Math.abs(node.x() - x) <= range && Math.abs(node.z() - z) <= range) {
                return Optional.of(node);
            }
        }
        return Optional.empty();
    }

    /** The ground height under a node as the generator gives it (the cave height for a sulfur node), if known. */
    public static Optional<Integer> groundOf(Node node) {
        return node.y() != Node.SURFACE ? Optional.of(node.y()) : Optional.ofNullable(GROUND.get(node.key()));
    }

    /**
     * The height of a surface node's pad: the middle value of the generator's ground height (the ground under any
     * water, so a pad by a pond sits on the ground and not up at the water's surface) at 13 points across the pad. Trees and other
     * features are not part of it. Worked out with the cell, on the worker thread; here only if that missed it.
     */
    public static int padHeight(ServerLevel level, Node node) {
        Integer known = PAD.get(node.key());
        if (known != null) return known;
        int y = NodeCells.padHeight(level, node.x(), node.z());
        PAD.put(node.key(), y);
        return y;
    }

    /** The nearest node of a type in this dimension, searched ring by ring. Runs where it is called. */
    public static Optional<Node> nearest(ServerLevel level, double x, double z, NodeType type, int maxCells) {
        return NodeSearch.nearest(level, x, z, type, maxCells);
    }

    public static Optional<Node> nearest(ServerLevel level, double x, double z, NodeType type, int maxCells, Terrain.Border border, long nanos) {
        return NodeSearch.nearest(level, x, z, type, maxCells, border, nanos);
    }

    /**
     * Runs a search on the worker thread and hands the answer to {@code then} on the server thread. The
     * world border is read now, on the calling (server) thread.
     */
    public static void nearestAsync(ServerLevel level, double x, double z, NodeType type, int maxCells, Consumer<Optional<Node>> then) {
        NodeSearch.nearestAsync(level, x, z, type, maxCells, then);
    }

    /** Runs a job on the worker thread (a search that combines several lookups), answering on the server thread (null if it failed). */
    public static <T> void async(MinecraftServer server, Supplier<T> job, Consumer<@Nullable T> then) {
        NodeWorker.async(server, job, then);
    }

    /**
     * Runs a long job in small pieces on the worker thread: each piece of {@code size} items is its own task, and the
     * next is queued only once it is done, so whatever else waits for the worker (a scanner, the cells of chunks being
     * generated) runs in between. {@code each} gets every piece's answer on the server thread (null if it failed), and
     * {@code done} runs there after the last.
     */
    public static <I, R> void batched(MinecraftServer server, List<I> items, int size, Function<List<I>, R> piece,
                                      Consumer<@Nullable R> each, Runnable done) {
        NodeWorker.batched(server, items, size, piece, each, done);
    }

    // ---- land in a cell

    /** A spot in a cell and its ground. */
    public record Spot(int x, int z, Terrain.Column column) {
    }

    /** Two biome samples: enough to tell void from land where the void biome fills whole columns. The full test comes after. */
    public static boolean quickVoid(ServerLevel level, int x, int z) {
        return NodeLand.quickVoid(level, x, z);
    }

    /** Which points of a cell's 5 x 5 grid have land by biome. Cached per cell and cell size. */
    public static int landMask(ServerLevel level, int cellX, int cellZ, int cell, int margin) {
        return NodeLand.landMask(level, cellX, cellZ, cell, margin);
    }

    /**
     * A dry spot on land among the cell's grid points, tried in an order set by the hash, nudged a little off
     * the grid when the nudged spot is land too. Null when the cell has none.
     */
    public static @Nullable Spot landSpot(ServerLevel level, int baseX, int baseZ, int span, int mask, long h) {
        return NodeLand.landSpot(level, baseX, baseZ, span, mask, h);
    }

    // ---- types and purity

    /** A purity drawn by the nodes' rule from a hash and a distance from spawn (sulfur geysers have theirs this way too). */
    public static Purity purityOf(long h, double distance) {
        return NodeRules.purity(h, distance);
    }

    public static long mix(long v) {
        v ^= v >>> 33;
        v *= 0xff51afd7ed558ccdL;
        v ^= v >>> 33;
        v *= 0xc4ceb9fe1a85ec53L;
        v ^= v >>> 33;
        return v;
    }
}
