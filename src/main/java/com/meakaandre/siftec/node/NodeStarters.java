package com.meakaandre.siftec.node;

import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/** Places the starter iron, copper and stone nodes in the cells round spawn, once per world. */
final class NodeStarters {
    /** Random spots tried for a starter node before the cell's nearest point. */
    private static final int STARTER_ATTEMPTS = 48;

    private NodeStarters() {
    }

    record Starter(NodeType type, NodeMap.Spot spot) {
    }

    /** Which cells round spawn hold the starter nodes (by cell key), worked out once per world. */
    static volatile @Nullable Map<Long, Starter> starters;
    /** The cells round spawn the starters may take, in order: the four beside it, the four corners, then its own. */
    private static final int[][] STARTER_CELLS = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}, {1, 1}, {-1, 1}, {1, -1}, {-1, -1}, {0, 0}};

    /**
     * Spawn always has a normal iron, copper and stone node within STARTER blocks. Each takes the next cell round
     * spawn that has a dry spot that near, so a sea or a river beside spawn only moves them on to another cell.
     */
    static Map<Long, Starter> starters(ServerLevel level, int[] from) {
        Map<Long, Starter> known = starters;
        if (known != null) return known;
        synchronized (NodeMap.class) {
            if (starters != null) return starters;
            Map<Long, Starter> out = new HashMap<>();
            NodeType[] types = {NodeType.IRON, NodeType.COPPER, NodeType.STONE};
            int scx = Math.floorDiv(from[0], NodeMap.CELL), scz = Math.floorDiv(from[1], NodeMap.CELL);
            for (int[] d : STARTER_CELLS) {
                if (out.size() == types.length) break;
                NodeMap.Spot spot = starterSpot(level, scx + d[0], scz + d[1], from);
                if (spot != null) out.put(NodeMap.cellKey(scx + d[0], scz + d[1]), new Starter(types[out.size()], spot));
            }
            starters = out;
            return out;
        }
    }

    /** A dry spot in the cell within STARTER blocks of spawn, or null: random spots first, then the cell's nearest point. */
    private static NodeMap.@Nullable Spot starterSpot(ServerLevel level, int cellX, int cellZ, int[] from) {
        int span = NodeMap.CELL - 2 * NodeMap.MARGIN;
        int baseX = cellX * NodeMap.CELL + NodeMap.MARGIN, baseZ = cellZ * NodeMap.CELL + NodeMap.MARGIN;
        long h = NodeMap.mix(level.getSeed() ^ NodeMap.mix(cellX * 0x9E3779B97F4A7C15L + cellZ * 0xC2B2AE3D27D4EB4FL + 0x57A47E5L));
        for (int attempt = 0; attempt < STARTER_ATTEMPTS; attempt++) {
            int x = baseX + (int) Long.remainderUnsigned(h, span);
            h = NodeMap.mix(h);
            int z = baseZ + (int) Long.remainderUnsigned(h, span);
            h = NodeMap.mix(h);
            if (Math.hypot(x - from[0], z - from[1]) > NodeMap.STARTER) continue;
            Terrain.Column column = Terrain.column(level, x, z);
            if (column != null && !column.water()) return new NodeMap.Spot(x, z, column);
        }
        int x = Math.clamp(from[0], baseX, baseX + span), z = Math.clamp(from[1], baseZ, baseZ + span);
        if (Math.hypot(x - from[0], z - from[1]) > NodeMap.STARTER) return null;
        Terrain.Column column = Terrain.column(level, x, z);
        return column != null && !column.water() ? new NodeMap.Spot(x, z, column) : null;
    }
}
