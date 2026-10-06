package com.meakaandre.siftec.node;

import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where every node is. Nothing is stored: a node's position, type and purity are worked out from the world
 * seed and the cell it is in, so the scanner can find nodes in chunks that have never been generated.
 *
 * The world is cut into cells of {@link #CELL} blocks. About 60% of cells hold one node.
 * Biomes are read from the world generator's biome source, which also needs no generated chunks.
 */
public final class NodeMap {
    public static final int CELL = 128;
    /** Nodes keep this far from the cell edge, so a whole mound always fits inside the cell. */
    private static final int MARGIN = 16;
    private static final float NODE_CHANCE = 0.6f;
    /** Within this many blocks of world spawn, most nodes are impure. */
    private static final int NEAR = 1500;
    private static final int FAR = 5000;

    private static final Map<Long, Optional<Node>> CACHE = new ConcurrentHashMap<>();
    private static long cacheSeed = Long.MIN_VALUE;

    private NodeMap() {
    }

    public static boolean hasNodes(Level level) {
        return level.dimension() == Level.OVERWORLD;
    }

    /**
     * False for the first moments of a new world, until its spawn point is settled and recorded. Nothing
     * may be looked up before that, or the map would be measured from the wrong place.
     */
    public static boolean ready(ServerLevel level) {
        return NodeSavedData.get(level.getServer()).hasOrigin();
    }

    /** Called on the first tick of a world: fixes the point the map is measured from. */
    public static void settle(ServerLevel level) {
        NodeSavedData data = NodeSavedData.get(level.getServer());
        if (data.hasOrigin()) return;
        var spawn = level.getRespawnData().pos();
        data.setOrigin(spawn.getX(), spawn.getZ());
        CACHE.clear();
    }

    /** The node in a cell, if it has one. */
    public static Optional<Node> inCell(ServerLevel level, int cellX, int cellZ) {
        if (!hasNodes(level) || !ready(level)) return Optional.empty();
        long seed = level.getSeed();
        if (seed != cacheSeed) {
            CACHE.clear();
            cacheSeed = seed;
        }
        long key = ((long) cellX << 32) ^ (cellZ & 0xffffffffL);
        Optional<Node> cached = CACHE.get(key);
        if (cached == null) {
            cached = compute(level, seed, cellX, cellZ);
            CACHE.put(key, cached);
        }
        return cached;
    }

    /** The node whose centre is within {@code range} blocks of this column, if any. */
    public static Optional<Node> near(ServerLevel level, int x, int z, int range) {
        Optional<Node> node = inCell(level, Math.floorDiv(x, CELL), Math.floorDiv(z, CELL));
        if (node.isPresent() && Math.abs(node.get().x() - x) <= range && Math.abs(node.get().z() - z) <= range) {
            return node;
        }
        return Optional.empty();
    }

    /** Every node whose centre is inside this chunk. */
    public static List<Node> inChunk(ServerLevel level, int chunkX, int chunkZ) {
        List<Node> nodes = new ArrayList<>(1);
        Optional<Node> node = inCell(level, Math.floorDiv(chunkX << 4, CELL), Math.floorDiv(chunkZ << 4, CELL));
        if (node.isPresent() && node.get().x() >> 4 == chunkX && node.get().z() >> 4 == chunkZ) {
            nodes.add(node.get());
        }
        return nodes;
    }

    /** The nearest node of a type (or of any type when {@code type} is null), searched ring by ring. */
    public static Optional<Node> nearest(ServerLevel level, double x, double z, NodeType type, int maxCells) {
        int cx = Math.floorDiv((int) Math.floor(x), CELL);
        int cz = Math.floorDiv((int) Math.floor(z), CELL);
        Node best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int ring = 0; ring <= maxCells; ring++) {
            // a node in ring r is at least (r - 1) cells away, so once the best is closer than that we are done
            if (best != null && bestDistance <= (ring - 1) * (double) CELL) break;
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    Optional<Node> node = inCell(level, cx + dx, cz + dz);
                    if (node.isEmpty() || type != null && node.get().type() != type) continue;
                    double distance = node.get().distanceTo(x, z);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = node.get();
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private static Optional<Node> compute(ServerLevel level, long seed, int cellX, int cellZ) {
        NodeSavedData data = NodeSavedData.get(level.getServer());
        int originX = data.originX(), originZ = data.originZ();
        int spawnCellX = Math.floorDiv(originX, CELL);
        int spawnCellZ = Math.floorDiv(originZ, CELL);

        long h = mix(seed ^ mix(cellX * 0x9E3779B97F4A7C15L + cellZ * 0xC2B2AE3D27D4EB4FL + 0x51F7EC));
        int span = CELL - 2 * MARGIN;
        int x = cellX * CELL + MARGIN + (int) Long.remainderUnsigned(h, span);
        h = mix(h);
        int z = cellZ * CELL + MARGIN + (int) Long.remainderUnsigned(h, span);
        h = mix(h);

        // the three cells next to spawn always hold a normal iron, copper and limestone node
        NodeType forced = null;
        if (cellX == spawnCellX + 1 && cellZ == spawnCellZ) forced = NodeType.IRON;
        else if (cellX == spawnCellX && cellZ == spawnCellZ + 1) forced = NodeType.COPPER;
        else if (cellX == spawnCellX - 1 && cellZ == spawnCellZ) forced = NodeType.LIMESTONE;
        if (forced != null) {
            // keep the starter nodes out of the sea: try other spots in the cell until one is on land
            for (int attempt = 0; attempt < 24 && isWater(biomeAt(level, x, z)); attempt++) {
                x = cellX * CELL + MARGIN + (int) Long.remainderUnsigned(h, span);
                h = mix(h);
                z = cellZ * CELL + MARGIN + (int) Long.remainderUnsigned(h, span);
                h = mix(h);
            }
            return Optional.of(new Node(x, z, forced, Purity.NORMAL));
        }

        float chance = (h >>> 40) / (float) (1 << 24);
        h = mix(h);
        if (chance >= NODE_CHANCE) return Optional.empty();

        Holder<Biome> biome = biomeAt(level, x, z);
        if (isWater(biome)) return Optional.empty();
        boolean hills = biome.is(BiomeTags.IS_MOUNTAIN) || biome.is(BiomeTags.IS_HILL);
        boolean hot = biome.is(BiomeTags.IS_JUNGLE) || biome.is(BiomeTags.IS_SAVANNA) || biome.is(BiomeTags.IS_BADLANDS);
        double distance = Math.sqrt(Math.pow(x - originX, 2) + Math.pow(z - originZ, 2));

        int total = 0;
        for (NodeType type : NodeType.values()) {
            if (allowed(type, hills, hot, distance)) total += type.weight;
        }
        int roll = (int) Long.remainderUnsigned(h, total);
        h = mix(h);
        NodeType picked = NodeType.IRON;
        for (NodeType type : NodeType.values()) {
            if (!allowed(type, hills, hot, distance)) continue;
            roll -= type.weight;
            if (roll < 0) {
                picked = type;
                break;
            }
        }

        // impure is the common one near spawn, pure the common one far out
        float t = (float) Math.clamp((distance - NEAR) / (FAR - NEAR), 0, 1);
        float impure = 0.5f - 0.3f * t;
        float pure = 0.1f + 0.3f * t;
        float p = (h >>> 40) / (float) (1 << 24);
        Purity purity = p < impure ? Purity.IMPURE : p < impure + pure ? Purity.PURE : Purity.NORMAL;
        return Optional.of(new Node(x, z, picked, purity));
    }

    private static boolean isWater(Holder<Biome> biome) {
        return biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_RIVER) || biome.is(BiomeTags.IS_DEEP_OCEAN);
    }

    private static boolean allowed(NodeType type, boolean hills, boolean hot, double distance) {
        if (distance < type.minDistance) return false;
        return switch (type.where) {
            case ANY -> true;
            case HILLS -> hills;
            case HOT -> hot;
        };
    }

    private static Holder<Biome> biomeAt(ServerLevel level, int x, int z) {
        return level.getUncachedNoiseBiome(
            QuartPos.fromBlock(x), QuartPos.fromBlock(level.getSeaLevel() + 8), QuartPos.fromBlock(z)
        );
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
