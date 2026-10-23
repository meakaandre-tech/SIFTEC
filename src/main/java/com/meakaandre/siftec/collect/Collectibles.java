package com.meakaandre.siftec.collect;

import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.node.Node;
import com.meakaandre.siftec.node.NodeMap;
import com.meakaandre.siftec.node.NodePlacer;
import com.meakaandre.siftec.node.NodeSavedData;
import com.meakaandre.siftec.node.Terrain;
import com.meakaandre.siftec.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where the Mercer Spheres, Somersloops and crash sites are. Like nodes, they are worked out from the world seed, one
 * possible spot per 96-block cell of the Overworld, so the Object Scanner can find them in land nobody has visited.
 * Somersloops also sit in Ancient City portal frames ({@link AncientCities}) and in some crash pods.
 */
public final class Collectibles {
    /** How blocks are set while placing: sent to clients, with no neighbour shape updates (they would load the next chunk). */
    private static final int WORLDGEN = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    public static final int CELL = 96;
    private static final int MARGIN = 8, FAR = 1000;
    private static final float CHANCE = 0.4f;
    /** A Somersloop's pocket lies between these heights (deepslate, above the bedrock). */
    private static final int DEEP_LOW = -52, DEEP_HIGH = -16;

    public record Spot(int x, int z, Collectible type) {
        public String key() {
            return x + "," + z;
        }
    }

    private static final Map<Long, Optional<Spot>> CACHE = new ConcurrentHashMap<>();
    private static volatile long cacheSeed = Long.MIN_VALUE;
    private static final Set<Long> PENDING = ConcurrentHashMap.newKeySet();

    private Collectibles() {
    }

    /** Forgets everything; called when the server stops. */
    public static void clear() {
        CACHE.clear();
        cacheSeed = Long.MIN_VALUE;
        AncientCities.clear();
    }

    private static long key(int cellX, int cellZ) {
        return ((long) cellX << 32) ^ (cellZ & 0xffffffffL);
    }

    /** True if the spot of the cell holding this chunk is already worked out. */
    public static boolean cached(ServerLevel level, int chunkX, int chunkZ) {
        if (level.dimension() != Level.OVERWORLD || !NodeMap.ready(level)) return true;
        return CACHE.containsKey(key(Math.floorDiv(chunkX << 4, CELL), Math.floorDiv(chunkZ << 4, CELL)));
    }

    public static void prefetch(ServerLevel level, int chunkX, int chunkZ) {
        int cellX = Math.floorDiv(chunkX << 4, CELL), cellZ = Math.floorDiv(chunkZ << 4, CELL);
        long key = key(cellX, cellZ);
        if (!PENDING.add(key)) return;
        NodeMap.async(level.getServer(), () -> {
            try {
                return inCell(level, cellX, cellZ);
            } finally {
                PENDING.remove(key);
            }
        }, spot -> {
        });
    }

    public static Optional<Spot> inCell(ServerLevel level, int cellX, int cellZ) {
        if (level.dimension() != Level.OVERWORLD || !NodeMap.ready(level)) return Optional.empty();
        int[] from = NodeMap.origin();
        if (from == null) return Optional.empty();
        long seed = level.getSeed();
        if (seed != cacheSeed) {
            CACHE.clear();
            cacheSeed = seed;
        }
        long key = key(cellX, cellZ);
        Optional<Spot> cached = CACHE.get(key);
        if (cached == null) {
            // bounded: a server that has seen much of the world starts the cache afresh
            if (CACHE.size() > 65_536) CACHE.clear();
            cached = compute(level, seed, cellX, cellZ, from[0], from[1]);
            CACHE.put(key, cached);
        }
        return cached;
    }

    private static Optional<Spot> compute(ServerLevel level, long seed, int cellX, int cellZ, int originX, int originZ) {
        long h = mix(seed ^ mix(cellX * 0x8CB92BA72F3D8DD7L + cellZ * 0xABC98388FB8FAC03L + 0xC011EC7));
        if ((h >>> 40) / (float) (1 << 24) >= CHANCE) return Optional.empty();
        h = mix(h);
        int span = CELL - 2 * MARGIN;
        int baseX = cellX * CELL + MARGIN, baseZ = cellZ * CELL + MARGIN;
        int x = baseX + (int) Long.remainderUnsigned(h, span);
        h = mix(h);
        int z = baseZ + (int) Long.remainderUnsigned(h, span);
        h = mix(h);
        boolean far = Math.sqrt(Math.pow(x - originX, 2) + Math.pow(z - originZ, 2)) >= FAR;
        int total = 0;
        for (Collectible c : Collectible.values()) if (far || !c.far) total += c.weight;
        int roll = (int) Long.remainderUnsigned(h, total);
        Collectible type = null;
        for (Collectible c : Collectible.values()) {
            if (!far && c.far) continue;
            roll -= c.weight;
            if (roll < 0) {
                type = c;
                break;
            }
        }
        if (type == null) return Optional.empty();
        // a Somersloop lies deep underground: the ground above it does not matter
        if (type == Collectible.SOMERSLOOP) return nearNode(level, x, z) ? Optional.empty() : Optional.of(new Spot(x, z, type));
        Terrain.Column column = Terrain.column(level, x, z);
        if (column == null) {
            // over void (floating islands): somewhere on the cell's land, or nowhere
            NodeMap.Spot spot = NodeMap.landSpot(level, baseX, baseZ, span, NodeMap.landMask(level, cellX, cellZ, CELL, MARGIN), mix(h ^ 0xC0FFEEL));
            if (spot == null) return Optional.empty();
            x = spot.x();
            z = spot.z();
        } else if (column.water()) {
            return Optional.empty();
        }
        // never on a node's patch or among its clump, nor where a node's site would cut it away
        if (nearNode(level, x, z)) return Optional.empty();
        return Optional.of(new Spot(x, z, type));
    }

    /** How far a collectible keeps from a node: the node's whole site, plus the room the wreckage or slate patch takes. */
    private static final int NODE_CLEARANCE = NodePlacer.REACH + 3;

    /** True if any node's site (a surface pad, a cave or Nether pad, its clump) reaches within clearance of the column. */
    private static boolean nearNode(ServerLevel level, int x, int z) {
        for (int cx = Math.floorDiv(x - NODE_CLEARANCE, NodeMap.CELL); cx <= Math.floorDiv(x + NODE_CLEARANCE, NodeMap.CELL); cx++) {
            for (int cz = Math.floorDiv(z - NODE_CLEARANCE, NodeMap.CELL); cz <= Math.floorDiv(z + NODE_CLEARANCE, NodeMap.CELL); cz++) {
                for (Node node : NodeMap.inCell(level, cx, cz)) {
                    if (Math.abs(node.x() - x) <= NODE_CLEARANCE && Math.abs(node.z() - z) <= NODE_CLEARANCE) return true;
                }
            }
        }
        return false;
    }

    /** The height a Somersloop's pocket is dug at, from the seed. */
    public static int deepY(long seed, int x, int z) {
        long h = mix(seed ^ mix(((long) x << 32) ^ (z & 0xffffffffL) ^ 0x5011L));
        return DEEP_LOW + (int) Long.remainderUnsigned(h, DEEP_HIGH - DEEP_LOW + 1);
    }

    /** The height to point at for a spot: the pocket for a Somersloop, the ground otherwise (or the fallback). */
    public static int y(ServerLevel level, Spot spot, int fallback) {
        if (spot.type() == Collectible.SOMERSLOOP) return deepY(level.getSeed(), spot.x(), spot.z());
        Terrain.Column column = Terrain.column(level, spot.x(), spot.z());
        return column == null ? fallback : column.y() + (spot.type() == Collectible.MERCER_SPHERE ? 2 : 1);
    }

    /** The nearest one of a kind that this set of already-collected spots does not hold. */
    public static Optional<Spot> nearest(ServerLevel level, double x, double z, Collectible type, Set<String> collected, int maxCells) {
        return nearest(level, x, z, type, collected, maxCells, Terrain.Border.NONE, Long.MAX_VALUE);
    }

    /**
     * As above, inside the world border and giving up (with the best so far) after {@code nanos}. Safe on the node
     * map's worker thread. Looking for Somersloops, the crash pods that carry one count too.
     */
    public static Optional<Spot> nearest(ServerLevel level, double x, double z, Collectible type, Set<String> collected, int maxCells,
                                         Terrain.Border border, long nanos) {
        long deadline = nanos == Long.MAX_VALUE ? Long.MAX_VALUE : System.nanoTime() + nanos;
        int cx = Math.floorDiv((int) Math.floor(x), CELL), cz = Math.floorDiv((int) Math.floor(z), CELL);
        Spot best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int ring = 0; ring <= maxCells; ring++) {
            if (best != null && bestDistance <= (ring - 1) * (double) CELL) break;
            if (System.nanoTime() > deadline) break;
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    int fromX = (cx + dx) * CELL, fromZ = (cz + dz) * CELL;
                    if (fromX + CELL <= border.minX() || fromX >= border.maxX() || fromZ + CELL <= border.minZ() || fromZ >= border.maxZ()) continue;
                    Optional<Spot> spot = inCell(level, cx + dx, cz + dz);
                    if (spot.isEmpty() || collected.contains(spot.get().key())) continue;
                    Spot s = spot.get();
                    boolean wanted = s.type() == type
                        || type == Collectible.SOMERSLOOP && s.type() == Collectible.CRASH_SITE && CrashSites.hasSomersloop(level, new BlockPos(s.x(), 0, s.z()));
                    if (!wanted || !border.contains(s.x(), s.z())) continue;
                    double d = Math.sqrt(Math.pow(s.x() - x, 2) + Math.pow(s.z() - z, 2));
                    if (d < bestDistance) {
                        bestDistance = d;
                        best = s;
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /** Puts the blocks down the first time their chunk loads, and notes any Ancient City starting in it. */
    public static boolean placeChunk(ServerLevel level, int chunkX, int chunkZ) {
        if (level.dimension() != Level.OVERWORLD) return false;
        AncientCities.noteChunk(level, chunkX, chunkZ);
        Optional<Spot> found = inCell(level, Math.floorDiv(chunkX << 4, CELL), Math.floorDiv(chunkZ << 4, CELL));
        if (found.isEmpty()) return false;
        Spot spot = found.get();
        if (spot.x() >> 4 != chunkX || spot.z() >> 4 != chunkZ) return false;
        NodeSavedData data = NodeSavedData.get(level.getServer());
        long piece = NodeSavedData.piece(mix(((long) spot.x() << 32) ^ spot.z() ^ 0x51065), chunkX, chunkZ);
        if (data.isPlaced(piece)) return false;
        data.markPlaced(piece);
        return place(level, spot, new ChunkPos(chunkX, chunkZ));
    }

    /** Puts one spot's blocks in the world, all of them (its chunks are loaded: the selftests). */
    public static boolean place(ServerLevel level, Spot spot) {
        return place(level, spot, null);
    }

    /**
     * Puts one spot's blocks in the world. With {@code only}, nothing outside that chunk is read or changed: the part of
     * a slate patch, pocket or wreckage that would reach into a neighbouring chunk is left out, so world generation never
     * loads or generates another chunk.
     */
    public static boolean place(ServerLevel level, Spot spot, @Nullable ChunkPos only) {
        BlockState block = ModBlocks.COLLECTIBLES.get(spot.type()).get().defaultBlockState();
        if (spot.type() == Collectible.SOMERSLOOP) {
            BlockPos at = new BlockPos(spot.x(), deepY(level.getSeed(), spot.x(), spot.z()), spot.z());
            pocket(level, at, only);
            level.setBlock(at, block, WORLDGEN);
            return true;
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spot.x(), spot.z());
        while (y > level.getMinY()) {
            BlockState below = level.getBlockState(pos.set(spot.x(), y - 1, spot.z()));
            // solid ground: not a tree, a plant or water
            if (!below.isAir() && !below.is(BlockTags.LOGS) && !below.is(BlockTags.LEAVES) && !below.canBeReplaced() && below.getFluidState().isEmpty()) break;
            y--;
        }
        if (y <= level.getMinY()) return false;
        BlockPos ground = new BlockPos(spot.x(), y - 1, spot.z());
        if (spot.type() == Collectible.MERCER_SPHERE) {
            siftslatePatch(level, ground, only);
            // it floats: one block of air between the patch and the sphere
            level.setBlock(ground.above(), Blocks.AIR.defaultBlockState(), WORLDGEN);
            level.setBlock(ground.above(2), block, WORLDGEN);
            return true;
        }
        level.setBlock(ground.above(), block, WORLDGEN);
        CrashSites.wreckage(level, ground.above(), only);
        return true;
    }

    /** The block Mercer Spheres hover over: The Sift's siftslate, or deepslate without The Sift. */
    private static BlockState siftslate() {
        return BuiltInRegistries.BLOCK.getOptional(Identifier.fromNamespaceAndPath("the_sift", "siftslate")).orElse(Blocks.DEEPSLATE).defaultBlockState();
    }

    /** A small rough disc of siftslate set into the ground. */
    /** True if the position may be touched: anywhere without a chunk to keep to, else only inside that chunk. */
    static boolean inside(@Nullable ChunkPos only, BlockPos at) {
        return only == null || at.getX() >> 4 == only.x() && at.getZ() >> 4 == only.z();
    }

    private static void siftslatePatch(ServerLevel level, BlockPos centre, @Nullable ChunkPos only) {
        BlockState slate = siftslate();
        long h = mix(((long) centre.getX() << 32) ^ centre.getZ() ^ 0x5D1F7L);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int d = dx * dx + dz * dz;
                if (d > 5 || d >= 4 && ((h >>> ((dx + 2) * 5 + dz + 2)) & 1) == 0) continue;  // ragged edge
                BlockPos at = centre.offset(dx, 0, dz);
                if (!inside(only, at)) continue;
                // settle onto the ground there: down past air and plants, a block or two at most
                for (int i = 0; i < 2 && level.getBlockState(at).canBeReplaced(); i++) at = at.below();
                BlockState was = level.getBlockState(at);
                if (was.canBeReplaced() || !was.getFluidState().isEmpty() || was.hasBlockEntity()) continue;
                level.setBlock(at, slate, WORLDGEN);
                if (level.getBlockState(at.above()).canBeReplaced() && level.getBlockState(at.above()).getFluidState().isEmpty()) {
                    level.setBlock(at.above(), Blocks.AIR.defaultBlockState(), WORLDGEN);
                }
            }
        }
    }

    /** A small hollow round a Somersloop: three by three, two high, its floor and walls left as they were. */
    private static void pocket(ServerLevel level, BlockPos at, @Nullable ChunkPos only) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 0; dy <= 1; dy++) {
                    BlockPos p = at.offset(dx, dy, dz);
                    if (!inside(only, p)) continue;
                    BlockState was = level.getBlockState(p);
                    if (was.is(Blocks.BEDROCK) || was.hasBlockEntity()) continue;
                    level.setBlock(p, Blocks.CAVE_AIR.defaultBlockState(), WORLDGEN);
                }
            }
        }
        BlockPos floor = at.below();
        if (level.getBlockState(floor).canBeReplaced()) level.setBlock(floor, Blocks.DEEPSLATE.defaultBlockState(), WORLDGEN);
    }

    /**
     * Opens the MAM tree a collectible starts (Alien Technology, the Hard Drive page) for the company, and says so the
     * first time.
     */
    public static void startTree(ServerPlayer player, Company company, Collectible type) {
        if (company.trees.add(type.tree)) {
            player.sendSystemMessage(Component.translatable("siftec.mam.tree_started", Component.translatable("siftec.tree." + type.tree)));
            Companies.save(player.level().getServer());
        }
    }

    static long mix(long v) {
        v ^= v >>> 33;
        v *= 0xff51afd7ed558ccdL;
        v ^= v >>> 33;
        v *= 0xc4ceb9fe1a85ec53L;
        v ^= v >>> 33;
        return v;
    }
}
