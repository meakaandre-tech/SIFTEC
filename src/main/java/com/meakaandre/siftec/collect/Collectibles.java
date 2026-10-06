package com.meakaandre.siftec.collect;

import com.meakaandre.siftec.node.NodeSavedData;
import com.meakaandre.siftec.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
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
    private static long cacheSeed = Long.MIN_VALUE;

    private Collectibles() {
    }

    public static Optional<Spot> inCell(ServerLevel level, int cellX, int cellZ) {
        if (level.dimension() != Level.OVERWORLD) return Optional.empty();
        NodeSavedData data = NodeSavedData.get(level.getServer());
        if (!data.hasOrigin()) return Optional.empty();
        long seed = level.getSeed();
        if (seed != cacheSeed) {
            CACHE.clear();
            cacheSeed = seed;
        }
        long key = ((long) cellX << 32) ^ (cellZ & 0xffffffffL);
        Optional<Spot> cached = CACHE.get(key);
        if (cached == null) {
            cached = compute(level, seed, cellX, cellZ, data.originX(), data.originZ());
            CACHE.put(key, cached);
        }
        return cached;
    }

    private static Optional<Spot> compute(ServerLevel level, long seed, int cellX, int cellZ, int originX, int originZ) {
        long h = mix(seed ^ mix(cellX * 0x8CB92BA72F3D8DD7L + cellZ * 0xABC98388FB8FAC03L + 0xC011EC7));
        if ((h >>> 40) / (float) (1 << 24) >= CHANCE) return Optional.empty();
        h = mix(h);
        int span = CELL - 2 * MARGIN;
        int x = cellX * CELL + MARGIN + (int) Long.remainderUnsigned(h, span);
        h = mix(h);
        int z = cellZ * CELL + MARGIN + (int) Long.remainderUnsigned(h, span);
        h = mix(h);
        Holder<Biome> biome = level.getUncachedNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(level.getSeaLevel() + 8), QuartPos.fromBlock(z));
        if (biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_DEEP_OCEAN) || biome.is(BiomeTags.IS_RIVER)) return Optional.empty();
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
        int cx = Math.floorDiv((int) Math.floor(x), CELL), cz = Math.floorDiv((int) Math.floor(z), CELL);
        Spot best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int ring = 0; ring <= maxCells; ring++) {
            if (best != null && bestDistance <= (ring - 1) * (double) CELL) break;
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    Optional<Spot> spot = inCell(level, cx + dx, cz + dz);
                    if (spot.isEmpty() || spot.get().type() != type || collected.contains(spot.get().key())) continue;
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
            if (!below.isAir() && !below.is(BlockTags.LOGS) && !below.canBeReplaced()) break;
            y--;
        }
        if (y <= level.getMinY() || !level.getFluidState(pos.set(spot.x(), y, spot.z())).isEmpty()) return false;
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
