package com.meakaandre.siftec.node;

import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Arrays;
import java.util.List;

/** Works out the surface (or Nether) node of one cell from the seed: its spot, type, purity and pad height. */
final class NodeCells {
    private static final float NODE_CHANCE = 0.6f;

    private NodeCells() {
    }

    /** Points round a pad's middle that must be dry: a pad with two or more of them in water is not placed. */
    private static final int[][] SHORE_POINTS = {{3, 0}, {-3, 0}, {0, 3}, {0, -3}};

    private static boolean padWet(ServerLevel level, int x, int z) {
        int wet = 0;
        for (int[] d : SHORE_POINTS) {
            if (Terrain.wet(level, x + d[0], z + d[1]) && ++wet >= 2) return true;
        }
        return false;
    }

    private static final int[][] PAD_POINTS = {{0, 0}, {2, 0}, {-2, 0}, {0, 2}, {0, -2}, {2, 2}, {2, -2}, {-2, 2}, {-2, -2}, {3, 0}, {-3, 0}, {0, 3}, {0, -3}};

    static int padHeight(ServerLevel level, int x, int z) {
        var generator = level.getChunkSource().getGenerator();
        var random = level.getChunkSource().randomState();
        int[] ys = new int[PAD_POINTS.length];
        for (int i = 0; i < ys.length; i++) {
            ys[i] = generator.getBaseHeight(x + PAD_POINTS[i][0], z + PAD_POINTS[i][1], Heightmap.Types.OCEAN_FLOOR_WG, level, random) - 1;
        }
        Arrays.sort(ys);
        return Math.max(ys[ys.length / 2], level.getMinY() + 1);
    }

    // ---- working out one cell

    static List<Node> nether(ServerLevel level, int cellX, int cellZ, int[] from) {
        long h = NodeMap.mix(level.getSeed() ^ NodeMap.mix(cellX * 0x9E3779B97F4A7C15L + cellZ * 0xC2B2AE3D27D4EB4FL + 0x4E37));
        int span = NodeMap.CELL - 2 * NodeMap.MARGIN;
        int x = cellX * NodeMap.CELL + NodeMap.MARGIN + (int) Long.remainderUnsigned(h, span);
        h = NodeMap.mix(h);
        int z = cellZ * NodeMap.CELL + NodeMap.MARGIN + (int) Long.remainderUnsigned(h, span);
        h = NodeMap.mix(h);
        // purity goes by the matching distance in the Overworld: 8 times as far in a vanilla Nether, the same in a 1:1 one
        double ratio = level.dimensionType().coordinateScale() / level.getServer().overworld().dimensionType().coordinateScale();
        double distance = Math.sqrt(Math.pow(x * ratio - from[0], 2) + Math.pow(z * ratio - from[1], 2));
        float chance = (h >>> 40) / (float) (1 << 24);
        h = NodeMap.mix(h);
        return chance < NODE_CHANCE ? List.of(new Node(x, z, NodeType.QUARTZ, NodeRules.purity(h, distance))) : List.of();
    }

    static List<Node> overworld(ServerLevel level, int cellX, int cellZ, int[] from) {
        long seed = level.getSeed();
        int originX = from[0], originZ = from[1];
        long h = NodeMap.mix(seed ^ NodeMap.mix(cellX * 0x9E3779B97F4A7C15L + cellZ * 0xC2B2AE3D27D4EB4FL + 0x51F7EC));
        int span = NodeMap.CELL - 2 * NodeMap.MARGIN;
        int baseX = cellX * NodeMap.CELL + NodeMap.MARGIN, baseZ = cellZ * NodeMap.CELL + NodeMap.MARGIN;
        int x = baseX + (int) Long.remainderUnsigned(h, span);
        h = NodeMap.mix(h);
        int z = baseZ + (int) Long.remainderUnsigned(h, span);
        h = NodeMap.mix(h);
        long spotHash = NodeMap.mix(h ^ 0x6C0A57L);

        // the starter iron, copper and stone nodes: normal, on dry land within STARTER blocks of spawn (see starters)
        NodeStarters.Starter starter = NodeStarters.starters(level, from).get(NodeMap.cellKey(cellX, cellZ));
        if (starter != null) {
            Node node = new Node(starter.spot().x(), starter.spot().z(), starter.type(), Purity.NORMAL);
            NodeMap.GROUND.put(node.key(), starter.spot().column().y());
            NodeMap.PAD.put(node.key(), padHeight(level, node.x(), node.z()));
            return List.of(node);
        }

        float chance = (h >>> 40) / (float) (1 << 24);
        h = NodeMap.mix(h);
        int mask = -1;
        if (chance >= NODE_CHANCE) {
            // no node, unless this cell holds a small island of its own: nothing around it would ever get one
            if (!NodeLand.quickVoid(level, x, z)) return List.of();
            mask = NodeLand.landMask(level, cellX, cellZ, NodeMap.CELL, NodeMap.MARGIN);
            if ((mask & NodeLand.ALL_POINTS) == 0) return List.of();
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                if ((NodeLand.landMask(level, cellX + d[0], cellZ + d[1], NodeMap.CELL, NodeMap.MARGIN) & NodeLand.ALL_POINTS) != 0) return List.of();
            }
        }

        Terrain.Column column = Terrain.column(level, x, z);
        if (column == null) {
            // over void: take a spot on the cell's land, if it has any
            if (mask == -1) mask = NodeLand.landMask(level, cellX, cellZ, NodeMap.CELL, NodeMap.MARGIN);
            NodeMap.Spot spot = NodeLand.landSpot(level, baseX, baseZ, span, mask, spotHash);
            if (spot == null) return List.of();
            x = spot.x();
            z = spot.z();
            column = spot.column();
        } else if (column.water()) {
            // no nodes in the sea, a river or a lake
            return List.of();
        }
        // nor on a lake's shore, where most of the pad would stand in the water on a pillar of fill
        if (padWet(level, x, z)) return List.of();
        Holder<Biome> biome = column.biome();
        double distance = Math.sqrt(Math.pow(x - originX, 2) + Math.pow(z - originZ, 2));
        int total = 0;
        for (NodeType type : NodeType.values()) {
            if (NodeRules.allowed(type, biome, distance)) total += type.weight;
        }
        int roll = (int) Long.remainderUnsigned(h, total);
        h = NodeMap.mix(h);
        NodeType picked = NodeType.IRON;
        for (NodeType type : NodeType.values()) {
            if (!NodeRules.allowed(type, biome, distance)) continue;
            roll -= type.weight;
            if (roll < 0) {
                picked = type;
                break;
            }
        }
        Node node = new Node(x, z, picked, NodeRules.purity(h, distance));
        NodeMap.GROUND.put(node.key(), column.y());
        NodeMap.PAD.put(node.key(), padHeight(level, x, z));
        return List.of(node);
    }
}
