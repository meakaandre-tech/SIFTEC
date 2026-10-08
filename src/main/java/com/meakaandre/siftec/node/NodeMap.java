package com.meakaandre.siftec.node;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

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
    private static final int MARGIN = 16;
    private static final float NODE_CHANCE = 0.6f;
    /** Within this many blocks of world spawn, most nodes are impure. */
    private static final int NEAR = 1500;
    private static final int FAR = 5000;
    /** A search for one resource gives up after this long, keeping the best it found. */
    public static final long SEARCH_NANOS = 4_000_000_000L;

    private static final ResourceKey<Biome> SULFUR_CAVES = ResourceKey.create(Registries.BIOME, Identifier.withDefaultNamespace("sulfur_caves"));
    private static final TagKey<Biome> COLD = biomeTag("is_cold"), DESERT = biomeTag("is_desert"), SWAMP = biomeTag("is_swamp");
    /** Where sulfur caves are looked for: these heights below the cell's ground, and these absolute heights (an ordinary world's caves). */
    private static final int CAVE_DEPTH = 136, CAVE_STEP = 16;
    private static final int[] CAVE_HEIGHTS = {-40, -24, -8, 8, 24, 40};

    private static final Map<ResourceKey<Level>, Map<Long, List<Node>>> CACHE = new ConcurrentHashMap<>();
    private static final Map<Long, Optional<Node>> SULFUR = new ConcurrentHashMap<>();
    /** Which of a cell's 5 x 5 sample points are over land (by biome); bit 25 marks "worked out". */
    private static final Map<Long, Integer> LAND = new ConcurrentHashMap<>();
    /** Ground height of surface nodes as the generator gives it, for waypoints and teleports. */
    private static final Map<Long, Integer> GROUND = new ConcurrentHashMap<>();
    private static volatile long cacheSeed = Long.MIN_VALUE;
    /** The point the map is measured from (world spawn when the world was new); null until settled. */
    private static volatile int @Nullable [] origin;
    /** Biome samples taken, for the selftest. */
    public static final AtomicLong SAMPLES = new AtomicLong();

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "SIFTEC node map");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });

    private NodeMap() {
    }

    private static TagKey<Biome> biomeTag(String path) {
        return TagKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath("c", path));
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
        origin = new int[]{data.originX(), data.originZ()};
    }

    private static void clearCaches() {
        CACHE.clear();
        SULFUR.clear();
        LAND.clear();
        GROUND.clear();
    }

    /** Forgets every worked-out cell (the selftest uses it to time a first scan). */
    public static void resetCaches() {
        clearCaches();
    }

    /** Forgets everything; called when the server stops, so the next world starts clean. */
    public static void clear() {
        clearCaches();
        origin = null;
        cacheSeed = Long.MIN_VALUE;
    }

    private static void checkSeed(ServerLevel level) {
        long seed = level.getSeed();
        if (seed != cacheSeed) {
            clearCaches();
            cacheSeed = seed;
        }
    }

    private static long cellKey(int cellX, int cellZ) {
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
        WORKER.execute(() -> {
            try {
                inCell(level, cellX, cellZ);
            } catch (RuntimeException e) {
                com.meakaandre.siftec.Siftec.LOGGER.warn("Could not work out node cell {} {}", cellX, cellZ, e);
            }
        });
    }

    /** The surface (or Nether) node of a cell, if any. */
    private static List<Node> surface(ServerLevel level, int cellX, int cellZ) {
        checkSeed(level);
        int[] from = origin;
        if (from == null) return List.of();
        Map<Long, List<Node>> cells = CACHE.computeIfAbsent(level.dimension(), k -> new ConcurrentHashMap<>());
        long key = cellKey(cellX, cellZ);
        List<Node> cached = cells.get(key);
        if (cached == null) {
            cached = level.dimension() == Level.NETHER ? nether(level, cellX, cellZ, from) : overworld(level, cellX, cellZ, from);
            cells.put(key, cached);
        }
        return cached;
    }

    private static Optional<Node> sulfurIn(ServerLevel level, int cellX, int cellZ) {
        if (level.dimension() != Level.OVERWORLD) return Optional.empty();
        checkSeed(level);
        int[] from = origin;
        if (from == null) return Optional.empty();
        long key = cellKey(cellX, cellZ);
        Optional<Node> cached = SULFUR.get(key);
        if (cached == null) {
            cached = Optional.ofNullable(sulfur(level, cellX, cellZ, from));
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
            if ((type == null || node.type() == type) && Math.abs(node.x() - x) <= range && Math.abs(node.z() - z) <= range) {
                return Optional.of(node);
            }
        }
        return Optional.empty();
    }

    /** The ground height under a node as the generator gives it (the cave height for a sulfur node), if known. */
    public static Optional<Integer> groundOf(Node node) {
        return node.y() != Node.SURFACE ? Optional.of(node.y()) : Optional.ofNullable(GROUND.get(node.key()));
    }

    /** The nearest node of a type in this dimension, searched ring by ring. Runs where it is called. */
    public static Optional<Node> nearest(ServerLevel level, double x, double z, NodeType type, int maxCells) {
        return nearest(level, x, z, type, maxCells, Terrain.Border.NONE, Long.MAX_VALUE);
    }

    public static Optional<Node> nearest(ServerLevel level, double x, double z, NodeType type, int maxCells, Terrain.Border border, long nanos) {
        if (!hasNodes(level) || origin == null || type.dimension() != level.dimension()) return Optional.empty();
        long deadline = nanos == Long.MAX_VALUE ? Long.MAX_VALUE : System.nanoTime() + nanos;
        int cx = Math.floorDiv((int) Math.floor(x), CELL);
        int cz = Math.floorDiv((int) Math.floor(z), CELL);
        boolean cave = type == NodeType.SULFUR;
        // finding a sulfur cave means testing the biome many times per cell, so that search stays closer
        if (cave) maxCells = Math.min(maxCells, 24);
        Node best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int ring = 0; ring <= maxCells; ring++) {
            // a node in ring r is at least (r - 1) cells away, so once the best is closer than that we are done
            if (best != null && bestDistance <= (ring - 1) * (double) CELL) break;
            if (System.nanoTime() > deadline) break;
            // past the world border there is nothing to offer
            if (ringOutside(border, cx, cz, ring)) {
                if (ring > 0 && ringOutside(border, cx, cz, ring - 1)) break;
                continue;
            }
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    if (cellOutside(border, cx + dx, cz + dz)) continue;
                    List<Node> nodes = cave ? sulfurIn(level, cx + dx, cz + dz).map(List::of).orElse(List.of()) : surface(level, cx + dx, cz + dz);
                    for (Node node : nodes) {
                        if (node.type() != type || !border.contains(node.x(), node.z())) continue;
                        double distance = node.distanceTo(x, z);
                        if (distance < bestDistance) {
                            bestDistance = distance;
                            best = node;
                        }
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private static boolean cellOutside(Terrain.Border border, int cx, int cz) {
        return ringOutside(border, cx, cz, 0);
    }

    /** True if the whole square ring of cells around (cx, cz) lies outside the border. */
    private static boolean ringOutside(Terrain.Border border, int cx, int cz, int ring) {
        double minX = (cx - ring) * (double) CELL, maxX = (cx + ring + 1) * (double) CELL;
        double minZ = (cz - ring) * (double) CELL, maxZ = (cz + ring + 1) * (double) CELL;
        boolean overlaps = maxX > border.minX() && minX < border.maxX() && maxZ > border.minZ() && minZ < border.maxZ();
        if (!overlaps) return true;
        if (ring == 0) return false;
        // the ring is the square minus its inside: outside if the inside covers all of the border
        double inMinX = (cx - ring + 1) * (double) CELL, inMaxX = (cx + ring) * (double) CELL;
        double inMinZ = (cz - ring + 1) * (double) CELL, inMaxZ = (cz + ring) * (double) CELL;
        return inMinX <= border.minX() && inMaxX >= border.maxX() && inMinZ <= border.minZ() && inMaxZ >= border.maxZ();
    }

    /**
     * Runs a search on the worker thread and hands the answer to {@code then} on the server thread. The
     * world border is read now, on the calling (server) thread.
     */
    public static void nearestAsync(ServerLevel level, double x, double z, NodeType type, int maxCells, Consumer<Optional<Node>> then) {
        Terrain.Border border = Terrain.Border.of(level);
        async(level.getServer(), () -> nearest(level, x, z, type, maxCells, border, SEARCH_NANOS), found -> then.accept(found == null ? Optional.empty() : found));
    }

    /** Runs a job on the worker thread (a search that combines several lookups), answering on the server thread (null if it failed). */
    public static <T> void async(MinecraftServer server, java.util.function.Supplier<T> job, Consumer<@Nullable T> then) {
        WORKER.execute(() -> {
            T result;
            try {
                result = job.get();
            } catch (RuntimeException e) {
                com.meakaandre.siftec.Siftec.LOGGER.warn("Background search failed", e);
                result = null;
            }
            T done = result;
            server.execute(() -> then.accept(done));
        });
    }

    // ---- working out one cell

    private static List<Node> nether(ServerLevel level, int cellX, int cellZ, int[] from) {
        long h = mix(level.getSeed() ^ mix(cellX * 0x9E3779B97F4A7C15L + cellZ * 0xC2B2AE3D27D4EB4FL + 0x4E37));
        int span = CELL - 2 * MARGIN;
        int x = cellX * CELL + MARGIN + (int) Long.remainderUnsigned(h, span);
        h = mix(h);
        int z = cellZ * CELL + MARGIN + (int) Long.remainderUnsigned(h, span);
        h = mix(h);
        // purity goes by the matching distance in the Overworld: 8 times as far in a vanilla Nether, the same in a 1:1 one
        double ratio = level.dimensionType().coordinateScale() / level.getServer().overworld().dimensionType().coordinateScale();
        double distance = Math.sqrt(Math.pow(x * ratio - from[0], 2) + Math.pow(z * ratio - from[1], 2));
        float chance = (h >>> 40) / (float) (1 << 24);
        h = mix(h);
        return chance < NODE_CHANCE ? List.of(new Node(x, z, NodeType.QUARTZ, purity(h, distance))) : List.of();
    }

    private static List<Node> overworld(ServerLevel level, int cellX, int cellZ, int[] from) {
        long seed = level.getSeed();
        int originX = from[0], originZ = from[1];
        long h = mix(seed ^ mix(cellX * 0x9E3779B97F4A7C15L + cellZ * 0xC2B2AE3D27D4EB4FL + 0x51F7EC));
        int span = CELL - 2 * MARGIN;
        int baseX = cellX * CELL + MARGIN, baseZ = cellZ * CELL + MARGIN;
        int x = baseX + (int) Long.remainderUnsigned(h, span);
        h = mix(h);
        int z = baseZ + (int) Long.remainderUnsigned(h, span);
        h = mix(h);
        long spotHash = mix(h ^ 0x6C0A57L);

        // the three cells next to spawn always hold a normal iron, copper and limestone node
        int spawnCellX = Math.floorDiv(originX, CELL), spawnCellZ = Math.floorDiv(originZ, CELL);
        NodeType forced = null;
        if (cellX == spawnCellX + 1 && cellZ == spawnCellZ) forced = NodeType.IRON;
        else if (cellX == spawnCellX && cellZ == spawnCellZ + 1) forced = NodeType.COPPER;
        else if (cellX == spawnCellX - 1 && cellZ == spawnCellZ) forced = NodeType.LIMESTONE;
        if (forced != null) {
            // keep the starter nodes on dry land: try other spots in the cell until one is
            Terrain.Column column = Terrain.column(level, x, z);
            for (int attempt = 0; attempt < 24 && (column == null || column.water()); attempt++) {
                x = baseX + (int) Long.remainderUnsigned(h, span);
                h = mix(h);
                z = baseZ + (int) Long.remainderUnsigned(h, span);
                h = mix(h);
                column = Terrain.column(level, x, z);
            }
            if (column == null || column.water()) {
                Spot spot = landSpot(level, baseX, baseZ, span, landMask(level, cellX, cellZ, CELL, MARGIN), spotHash);
                if (spot == null) return List.of();
                x = spot.x();
                z = spot.z();
                column = spot.column();
            }
            Node node = new Node(x, z, forced, Purity.NORMAL);
            GROUND.put(node.key(), column.y());
            return List.of(node);
        }

        float chance = (h >>> 40) / (float) (1 << 24);
        h = mix(h);
        int mask = -1;
        if (chance >= NODE_CHANCE) {
            // no node, unless this cell holds a small island of its own: nothing around it would ever get one
            if (!quickVoid(level, x, z)) return List.of();
            mask = landMask(level, cellX, cellZ, CELL, MARGIN);
            if ((mask & ALL_POINTS) == 0) return List.of();
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                if ((landMask(level, cellX + d[0], cellZ + d[1], CELL, MARGIN) & ALL_POINTS) != 0) return List.of();
            }
        }

        Terrain.Column column = Terrain.column(level, x, z);
        if (column == null) {
            // over void: take a spot on the cell's land, if it has any
            if (mask == -1) mask = landMask(level, cellX, cellZ, CELL, MARGIN);
            Spot spot = landSpot(level, baseX, baseZ, span, mask, spotHash);
            if (spot == null) return List.of();
            x = spot.x();
            z = spot.z();
            column = spot.column();
        } else if (column.water()) {
            // no nodes in the sea
            return List.of();
        }
        Holder<Biome> biome = column.biome();
        double distance = Math.sqrt(Math.pow(x - originX, 2) + Math.pow(z - originZ, 2));
        int total = 0;
        for (NodeType type : NodeType.values()) {
            if (allowed(type, biome, distance)) total += type.weight;
        }
        int roll = (int) Long.remainderUnsigned(h, total);
        h = mix(h);
        NodeType picked = NodeType.IRON;
        for (NodeType type : NodeType.values()) {
            if (!allowed(type, biome, distance)) continue;
            roll -= type.weight;
            if (roll < 0) {
                picked = type;
                break;
            }
        }
        Node node = new Node(x, z, picked, purity(h, distance));
        GROUND.put(node.key(), column.y());
        return List.of(node);
    }

    // ---- land in a cell

    /** A spot in a cell and its ground. */
    public record Spot(int x, int z, Terrain.Column column) {
    }

    private static final int GRID = 5, ALL_POINTS = (1 << GRID * GRID) - 1, DONE = 1 << 25;

    /** Two biome samples: enough to tell void from land where the void biome fills whole columns. The full test comes after. */
    public static boolean quickVoid(ServerLevel level, int x, int z) {
        SAMPLES.addAndGet(2);
        int min = level.getMinY(), max = level.getMaxY();
        return Terrain.isVoid(Terrain.biome(level, x, Math.clamp(64, min, max), z))
            && Terrain.isVoid(Terrain.biome(level, x, (min + max) / 2, z));
    }

    private static int gridX(int base, int span, int i) {
        return base + span * (i % GRID) / (GRID - 1);
    }

    private static int gridZ(int base, int span, int i) {
        return base + span * (i / GRID) / (GRID - 1);
    }

    /** Which points of a cell's 5 x 5 grid have land by biome. Cached per cell and cell size. */
    public static int landMask(ServerLevel level, int cellX, int cellZ, int cell, int margin) {
        long key = cellKey(cellX, cellZ) * 31 + cell;
        Integer known = LAND.get(key);
        if (known != null) return known;
        int span = cell - 2 * margin, baseX = cellX * cell + margin, baseZ = cellZ * cell + margin;
        int mask = DONE;
        for (int i = 0; i < GRID * GRID; i++) {
            if (!quickVoid(level, gridX(baseX, span, i), gridZ(baseZ, span, i))) mask |= 1 << i;
        }
        LAND.put(key, mask);
        return mask;
    }

    /**
     * A dry spot on land among the cell's grid points, tried in an order set by the hash, nudged a little off
     * the grid when the nudged spot is land too. Null when the cell has none.
     */
    public static @Nullable Spot landSpot(ServerLevel level, int baseX, int baseZ, int span, int mask, long h) {
        int[] order = new int[GRID * GRID];
        for (int i = 0; i < order.length; i++) order[i] = i;
        for (int i = order.length - 1; i > 0; i--) {
            h = mix(h);
            int j = (int) Long.remainderUnsigned(h, i + 1);
            int t = order[i];
            order[i] = order[j];
            order[j] = t;
        }
        int probes = 0;
        for (int i : order) {
            if ((mask & (1 << i)) == 0) continue;
            if (++probes > 8) break;
            int x = gridX(baseX, span, i), z = gridZ(baseZ, span, i);
            Terrain.Column column = Terrain.column(level, x, z);
            if (column == null || column.water()) continue;
            h = mix(h);
            int nx = Math.clamp(x + (int) Long.remainderUnsigned(h, 13) - 6, baseX, baseX + span);
            int nz = Math.clamp(z + (int) Long.remainderUnsigned(h >>> 20, 13) - 6, baseZ, baseZ + span);
            Terrain.Column nudged = Terrain.column(level, nx, nz);
            if (nudged != null && !nudged.water()) return new Spot(nx, nz, nudged);
            return new Spot(x, z, column);
        }
        return null;
    }

    // ---- sulfur caves

    /**
     * A sulfur node, if a sulfur cave pocket lies under this cell's land. Looks on a coarse grid (no pocket of
     * radius 40 or more can slip between its points) at heights below the cell's ground and at the heights of an
     * ordinary world's caves, then finds the middle of the pocket. The cell that holds the middle owns the node.
     */
    private static @Nullable Node sulfur(ServerLevel level, int cellX, int cellZ, int[] from) {
        int baseX = cellX * CELL, baseZ = cellZ * CELL;
        int[] offsets = {24, 64, 104};
        Integer ground = null;
        int[] heights = null;
        List<int[]> hits = new ArrayList<>();
        for (int ox : offsets) {
            for (int oz : offsets) {
                int x = baseX + ox, z = baseZ + oz;
                if (quickVoid(level, x, z)) continue;
                if (ground == null) {
                    Terrain.Column column = Terrain.column(level, x, z);
                    if (column == null) continue;
                    ground = column.y();
                    heights = caveHeights(level, ground);
                }
                for (int y : heights) {
                    SAMPLES.incrementAndGet();
                    if (Terrain.biome(level, x, y, z).is(SULFUR_CAVES)) hits.add(new int[]{x, y, z});
                }
            }
        }
        if (hits.isEmpty()) return null;
        double sx = 0, sz = 0;
        int[] ys = new int[hits.size()];
        for (int i = 0; i < hits.size(); i++) {
            sx += hits.get(i)[0];
            sz += hits.get(i)[2];
            ys[i] = hits.get(i)[1];
        }
        Arrays.sort(ys);
        int y = ys[ys.length / 2];
        int cx = (int) Math.round(sx / hits.size()), cz = (int) Math.round(sz / hits.size());
        // the middle of the pocket, on a finer grid at that height
        double fx = 0, fz = 0;
        int n = 0;
        for (int dx = -40; dx <= 40; dx += 8) {
            for (int dz = -40; dz <= 40; dz += 8) {
                SAMPLES.incrementAndGet();
                if (Terrain.biome(level, cx + dx, y, cz + dz).is(SULFUR_CAVES)) {
                    fx += cx + dx;
                    fz += cz + dz;
                    n++;
                }
            }
        }
        if (n > 0) {
            cx = (int) Math.round(fx / n);
            cz = (int) Math.round(fz / n);
        }
        if (Math.floorDiv(cx, CELL) != cellX || Math.floorDiv(cz, CELL) != cellZ) return null;
        cx = Math.clamp(cx, baseX + MARGIN, baseX + CELL - MARGIN - 1);
        cz = Math.clamp(cz, baseZ + MARGIN, baseZ + CELL - MARGIN - 1);
        long h = mix(level.getSeed() ^ mix(cellX * 0xD6E8FEB86659FD93L + cellZ * 0xA0761D6478BD642FL + 0x5F1F));
        double distance = Math.sqrt(Math.pow(cx - from[0], 2) + Math.pow(cz - from[1], 2));
        return new Node(cx, cz, NodeType.SULFUR, purity(h, distance), y);
    }

    private static int[] caveHeights(ServerLevel level, int ground) {
        int min = level.getMinY() + 4;
        List<Integer> heights = new ArrayList<>();
        for (int y = ground - 8; y >= Math.max(min, ground - CAVE_DEPTH); y -= CAVE_STEP) heights.add(y);
        for (int y : CAVE_HEIGHTS) {
            if (y >= min && y < ground - 4 && heights.stream().noneMatch(o -> Math.abs(o - y) < 8)) heights.add(y);
        }
        return heights.stream().mapToInt(Integer::intValue).toArray();
    }

    // ---- types and purity

    /** Impure is the common one near spawn, pure the common one far out. */
    private static Purity purity(long h, double distance) {
        float t = (float) Math.clamp((distance - NEAR) / (FAR - NEAR), 0, 1);
        float impure = 0.5f - 0.3f * t;
        float pure = 0.1f + 0.3f * t;
        float p = (mix(h) >>> 40) / (float) (1 << 24);
        return p < impure ? Purity.IMPURE : p < impure + pure ? Purity.PURE : Purity.NORMAL;
    }

    private static boolean allowed(NodeType type, Holder<Biome> biome, double distance) {
        if (distance < type.minDistance) return false;
        return switch (type.where) {
            case ANY -> true;
            case HILLS -> biome.is(BiomeTags.IS_MOUNTAIN) || biome.is(BiomeTags.IS_HILL);
            case HOT -> biome.is(BiomeTags.IS_JUNGLE) || biome.is(BiomeTags.IS_SAVANNA) || biome.is(BiomeTags.IS_BADLANDS);
            case COLD -> biome.is(COLD) || biome.is(BiomeTags.IS_MOUNTAIN);
            case OILY -> biome.is(DESERT) || biome.is(SWAMP) || biome.is(BiomeTags.IS_BADLANDS) || biome.is(BiomeTags.IS_BEACH);
            case NETHER, SULFUR_CAVE -> false;
        };
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
