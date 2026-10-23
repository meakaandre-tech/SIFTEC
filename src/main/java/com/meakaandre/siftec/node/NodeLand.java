package com.meakaandre.siftec.node;

import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

/** Tells land from void in a cell (a 5 x 5 grid of biome samples) and picks a dry spot on that land. */
final class NodeLand {
    /** Land grid points tried at most for a dry spot, and how far (each way) a spot may be nudged off its point. */
    private static final int LAND_PROBES = 8, NUDGE = 6;

    static final int GRID = 5, ALL_POINTS = (1 << GRID * GRID) - 1, DONE = 1 << 25;

    private NodeLand() {
    }

    /** Two biome samples: enough to tell void from land where the void biome fills whole columns. The full test comes after. */
    static boolean quickVoid(ServerLevel level, int x, int z) {
        NodeMap.SAMPLES.addAndGet(2);
        int min = level.getMinY(), max = level.getMaxY();
        return Terrain.isVoid(Terrain.biome(level, x, Math.clamp(Terrain.SAMPLE_Y, min, max), z))
            && Terrain.isVoid(Terrain.biome(level, x, (min + max) / 2, z));
    }

    private static int gridX(int base, int span, int i) {
        return base + span * (i % GRID) / (GRID - 1);
    }

    private static int gridZ(int base, int span, int i) {
        return base + span * (i / GRID) / (GRID - 1);
    }

    /** Which points of a cell's 5 x 5 grid have land by biome. Cached per cell and cell size. */
    static int landMask(ServerLevel level, int cellX, int cellZ, int cell, int margin) {
        long key = NodeMap.cellKey(cellX, cellZ) * 31 + cell;
        Integer known = NodeMap.LAND.get(key);
        if (known != null) return known;
        int span = cell - 2 * margin, baseX = cellX * cell + margin, baseZ = cellZ * cell + margin;
        int mask = DONE;
        for (int i = 0; i < GRID * GRID; i++) {
            if (!quickVoid(level, gridX(baseX, span, i), gridZ(baseZ, span, i))) mask |= 1 << i;
        }
        NodeMap.LAND.put(key, mask);
        return mask;
    }

    /**
     * A dry spot on land among the cell's grid points, tried in an order set by the hash, nudged a little off
     * the grid when the nudged spot is land too. Null when the cell has none.
     */
    static NodeMap.@Nullable Spot landSpot(ServerLevel level, int baseX, int baseZ, int span, int mask, long h) {
        int[] order = new int[GRID * GRID];
        for (int i = 0; i < order.length; i++) order[i] = i;
        for (int i = order.length - 1; i > 0; i--) {
            h = NodeMap.mix(h);
            int j = (int) Long.remainderUnsigned(h, i + 1);
            int t = order[i];
            order[i] = order[j];
            order[j] = t;
        }
        int probes = 0;
        for (int i : order) {
            if ((mask & (1 << i)) == 0) continue;
            if (++probes > LAND_PROBES) break;
            int x = gridX(baseX, span, i), z = gridZ(baseZ, span, i);
            Terrain.Column column = Terrain.column(level, x, z);
            if (column == null || column.water()) continue;
            h = NodeMap.mix(h);
            int nx = Math.clamp(x + (int) Long.remainderUnsigned(h, 2 * NUDGE + 1) - NUDGE, baseX, baseX + span);
            int nz = Math.clamp(z + (int) Long.remainderUnsigned(h >>> 20, 2 * NUDGE + 1) - NUDGE, baseZ, baseZ + span);
            Terrain.Column nudged = Terrain.column(level, nx, nz);
            if (nudged != null && !nudged.water()) return new NodeMap.Spot(nx, nz, nudged);
            return new NodeMap.Spot(x, z, column);
        }
        return null;
    }
}
