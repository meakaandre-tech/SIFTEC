package com.meakaandre.siftec.node;

import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.TagKey;
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
 * The world is cut into cells of {@link #CELL} blocks. In the Overworld about 60% of cells hold a surface
 * node, and a cell that has a sulfur cave under it also holds a sulfur node inside that cave.
 * In the Nether about 60% of cells hold a quartz node.
 * Biomes are read from the world generator, which also needs no generated chunks.
 */
public final class NodeMap {
    public static final int CELL = 128;
    /** Nodes keep this far from the cell edge, so a whole mound always fits inside the cell. */
    private static final int MARGIN = 16;
    private static final float NODE_CHANCE = 0.6f;
    /** Within this many blocks of world spawn, most nodes are impure. */
    private static final int NEAR = 1500;
    private static final int FAR = 5000;

    private static final ResourceKey<Biome> SULFUR_CAVES = ResourceKey.create(Registries.BIOME, Identifier.withDefaultNamespace("sulfur_caves"));
    private static final TagKey<Biome> COLD = biomeTag("is_cold"), DESERT = biomeTag("is_desert"), SWAMP = biomeTag("is_swamp");
    private static final int[] CAVE_HEIGHTS = {-40, -24, -8, 8, 24, 40};

    private static final Map<ResourceKey<Level>, Map<Long, List<Node>>> CACHE = new ConcurrentHashMap<>();
    private static long cacheSeed = Long.MIN_VALUE;

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
        return NodeSavedData.get(level.getServer()).hasOrigin();
    }

    /** Called on the first tick of a world: fixes the point the map is measured from. */
    public static void settle(ServerLevel level) {
        NodeSavedData data = NodeSavedData.get(level.getServer());
        if (data.hasOrigin()) return;
        var spawn = level.getServer().overworld().getRespawnData().pos();
        data.setOrigin(spawn.getX(), spawn.getZ());
        CACHE.clear();
    }

    /** The nodes in a cell: none, one, or (with a sulfur cave below) two. */
    public static List<Node> inCell(ServerLevel level, int cellX, int cellZ) {
        if (!hasNodes(level) || !ready(level)) return List.of();
        long seed = level.getSeed();
        if (seed != cacheSeed) {
            CACHE.clear();
            cacheSeed = seed;
        }
        Map<Long, List<Node>> cells = CACHE.computeIfAbsent(level.dimension(), k -> new ConcurrentHashMap<>());
        long key = ((long) cellX << 32) ^ (cellZ & 0xffffffffL);
        List<Node> cached = cells.get(key);
        if (cached == null) {
            cached = compute(level, seed, cellX, cellZ);
            cells.put(key, cached);
        }
        return cached;
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

    /** The nearest node of a type in this dimension, searched ring by ring. */
    public static Optional<Node> nearest(ServerLevel level, double x, double z, NodeType type, int maxCells) {
        int cx = Math.floorDiv((int) Math.floor(x), CELL);
        int cz = Math.floorDiv((int) Math.floor(z), CELL);
        // finding a sulfur cave means testing the biome many times per cell, so that search stays closer
        if (type == NodeType.SULFUR) maxCells = Math.min(maxCells, 24);
        Node best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int ring = 0; ring <= maxCells; ring++) {
            // a node in ring r is at least (r - 1) cells away, so once the best is closer than that we are done
            if (best != null && bestDistance <= (ring - 1) * (double) CELL) break;
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    for (Node node : inCell(level, cx + dx, cz + dz)) {
                        if (node.type() != type) continue;
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

    private static List<Node> compute(ServerLevel level, long seed, int cellX, int cellZ) {
        NodeSavedData data = NodeSavedData.get(level.getServer());
        int originX = data.originX(), originZ = data.originZ();
        boolean nether = level.dimension() == Level.NETHER;
        long h = mix(seed ^ mix(cellX * 0x9E3779B97F4A7C15L + cellZ * 0xC2B2AE3D27D4EB4FL + (nether ? 0x4E37 : 0x51F7EC)));
        int span = CELL - 2 * MARGIN;
        int x = cellX * CELL + MARGIN + (int) Long.remainderUnsigned(h, span);
        h = mix(h);
        int z = cellZ * CELL + MARGIN + (int) Long.remainderUnsigned(h, span);
        h = mix(h);
        double distance = Math.sqrt(Math.pow(x - originX, 2) + Math.pow(z - originZ, 2));

        if (nether) {
            float chance = (h >>> 40) / (float) (1 << 24);
            h = mix(h);
            return chance < NODE_CHANCE ? List.of(new Node(x, z, NodeType.QUARTZ, purity(h, distance * 8))) : List.of();
        }

        List<Node> nodes = new ArrayList<>(2);
        Node cave = sulfur(level, seed, cellX, cellZ, originX, originZ);
        if (cave != null) nodes.add(cave);

        // the three cells next to spawn always hold a normal iron, copper and limestone node
        int spawnCellX = Math.floorDiv(originX, CELL), spawnCellZ = Math.floorDiv(originZ, CELL);
        NodeType forced = null;
        if (cellX == spawnCellX + 1 && cellZ == spawnCellZ) forced = NodeType.IRON;
        else if (cellX == spawnCellX && cellZ == spawnCellZ + 1) forced = NodeType.COPPER;
        else if (cellX == spawnCellX - 1 && cellZ == spawnCellZ) forced = NodeType.LIMESTONE;
        if (forced != null) {
            // keep the starter nodes out of the sea: try other spots in the cell until one is on land
            for (int attempt = 0; attempt < 24 && isWater(biomeAt(level, x, level.getSeaLevel() + 8, z)); attempt++) {
                x = cellX * CELL + MARGIN + (int) Long.remainderUnsigned(h, span);
                h = mix(h);
                z = cellZ * CELL + MARGIN + (int) Long.remainderUnsigned(h, span);
                h = mix(h);
            }
            nodes.add(new Node(x, z, forced, Purity.NORMAL));
            return nodes;
        }

        float chance = (h >>> 40) / (float) (1 << 24);
        h = mix(h);
        if (chance >= NODE_CHANCE) return nodes;

        Holder<Biome> biome = biomeAt(level, x, level.getSeaLevel() + 8, z);
        if (isWater(biome)) return nodes;
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
        nodes.add(new Node(x, z, picked, purity(h, distance)));
        return nodes;
    }

    /** A sulfur node, if some spot under this cell is in a sulfur cave. */
    private static Node sulfur(ServerLevel level, long seed, int cellX, int cellZ, int originX, int originZ) {
        long h = mix(seed ^ mix(cellX * 0xD6E8FEB86659FD93L + cellZ * 0xA0761D6478BD642FL + 0x5F1F));
        int span = CELL - 2 * MARGIN;
        for (int attempt = 0; attempt < 4; attempt++) {
            int x = cellX * CELL + MARGIN + (int) Long.remainderUnsigned(h, span);
            h = mix(h);
            int z = cellZ * CELL + MARGIN + (int) Long.remainderUnsigned(h, span);
            h = mix(h);
            for (int y : CAVE_HEIGHTS) {
                if (biomeAt(level, x, y, z).is(SULFUR_CAVES)) {
                    double distance = Math.sqrt(Math.pow(x - originX, 2) + Math.pow(z - originZ, 2));
                    return new Node(x, z, NodeType.SULFUR, purity(h, distance), y);
                }
            }
        }
        return null;
    }

    /** Impure is the common one near spawn, pure the common one far out. */
    private static Purity purity(long h, double distance) {
        float t = (float) Math.clamp((distance - NEAR) / (FAR - NEAR), 0, 1);
        float impure = 0.5f - 0.3f * t;
        float pure = 0.1f + 0.3f * t;
        float p = (mix(h) >>> 40) / (float) (1 << 24);
        return p < impure ? Purity.IMPURE : p < impure + pure ? Purity.PURE : Purity.NORMAL;
    }

    private static boolean isWater(Holder<Biome> biome) {
        return biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_RIVER) || biome.is(BiomeTags.IS_DEEP_OCEAN);
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

    private static Holder<Biome> biomeAt(ServerLevel level, int x, int y, int z) {
        return level.getUncachedNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z));
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
