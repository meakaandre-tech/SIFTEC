package com.meakaandre.siftec.collect;

import com.meakaandre.siftec.node.NodeMap;
import com.meakaandre.siftec.node.NodeSavedData;
import com.meakaandre.siftec.node.Terrain;
import com.meakaandre.siftec.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where the slugs and alien artefacts are. Like nodes, they are worked out from the world seed, one
 * possible spot per 96-block cell of the Overworld, so the Object Scanner can find them in land nobody
 * has visited.
 */
public final class Collectibles {
    public static final int CELL = 96;
    private static final int MARGIN = 8, FAR = 1000;
    private static final float CHANCE = 0.4f;

    public record Spot(int x, int z, Collectible type) {
        public String key() {
            return x + "," + z;
        }
    }

    private static final Map<Long, Optional<Spot>> CACHE = new ConcurrentHashMap<>();
    private static volatile long cacheSeed = Long.MIN_VALUE;

    private Collectibles() {
    }

    /** Forgets everything; called when the server stops. */
    public static void clear() {
        CACHE.clear();
        cacheSeed = Long.MIN_VALUE;
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
        NodeMap.async(level.getServer(), () -> inCell(level, cellX, cellZ), spot -> {
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
        boolean far = Math.sqrt(Math.pow(x - originX, 2) + Math.pow(z - originZ, 2)) >= FAR;
        int total = 0;
        for (Collectible c : Collectible.values()) if (far || !c.far) total += c.weight;
        int roll = (int) Long.remainderUnsigned(h, total);
        for (Collectible c : Collectible.values()) {
            if (!far && c.far) continue;
            roll -= c.weight;
            if (roll < 0) return Optional.of(new Spot(x, z, c));
        }
        return Optional.empty();
    }

    /** The nearest one of a kind that this set of already-collected spots does not hold. */
    public static Optional<Spot> nearest(ServerLevel level, double x, double z, Collectible type, java.util.Set<String> collected, int maxCells) {
        return nearest(level, x, z, type, collected, maxCells, Terrain.Border.NONE, Long.MAX_VALUE);
    }

    /** As above, inside the world border and giving up (with the best so far) after {@code nanos}. Safe on the node map's worker thread. */
    public static Optional<Spot> nearest(ServerLevel level, double x, double z, Collectible type, java.util.Set<String> collected, int maxCells,
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
                    if (spot.isEmpty() || spot.get().type() != type || collected.contains(spot.get().key())) continue;
                    if (!border.contains(spot.get().x(), spot.get().z())) continue;
                    double d = Math.sqrt(Math.pow(spot.get().x() - x, 2) + Math.pow(spot.get().z() - z, 2));
                    if (d < bestDistance) {
                        bestDistance = d;
                        best = spot.get();
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /** Puts the block down the first time its chunk loads. */
    public static boolean placeChunk(ServerLevel level, int chunkX, int chunkZ) {
        Optional<Spot> found = inCell(level, Math.floorDiv(chunkX << 4, CELL), Math.floorDiv(chunkZ << 4, CELL));
        if (found.isEmpty()) return false;
        Spot spot = found.get();
        if (spot.x() >> 4 != chunkX || spot.z() >> 4 != chunkZ) return false;
        NodeSavedData data = NodeSavedData.get(level.getServer());
        long piece = NodeSavedData.piece(mix(((long) spot.x() << 32) ^ spot.z() ^ 0x51065), chunkX, chunkZ);
        if (data.isPlaced(piece)) return false;
        data.markPlaced(piece);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spot.x(), spot.z());
        while (y > level.getMinY()) {
            BlockState below = level.getBlockState(pos.set(spot.x(), y - 1, spot.z()));
            // solid ground: not a tree, a plant or water
            if (!below.isAir() && !below.is(BlockTags.LOGS) && !below.is(BlockTags.LEAVES) && !below.canBeReplaced() && below.getFluidState().isEmpty()) break;
            y--;
        }
        if (y <= level.getMinY()) return false;
        level.setBlock(pos.set(spot.x(), y, spot.z()), ModBlocks.COLLECTIBLES.get(spot.type()).get().defaultBlockState(), Block.UPDATE_CLIENTS);
        if (spot.type() == Collectible.CRASH_SITE) CrashSites.wreckage(level, new BlockPos(spot.x(), y, spot.z()));
        return true;
    }

    private static long mix(long v) {
        v ^= v >>> 33;
        v *= 0xff51afd7ed558ccdL;
        v ^= v >>> 33;
        v *= 0xc4ceb9fe1a85ec53L;
        v ^= v >>> 33;
        return v;
    }
}
