package com.meakaandre.siftec.node;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Finds the sulfur cave pocket under a cell's land, and the sulfur node at its middle. */
final class NodeCaves {
    private static final ResourceKey<Biome> SULFUR_CAVES = ResourceKey.create(Registries.BIOME, Identifier.withDefaultNamespace("sulfur_caves"));
    /** Where sulfur caves are looked for: these heights below the cell's ground, and these absolute heights (an ordinary world's caves). */
    private static final int CAVE_DEPTH = 136, CAVE_STEP = 16;
    private static final int[] CAVE_HEIGHTS = {-40, -24, -8, 8, 24, 40};
    /** The first height looked at below the ground, the least a height stays under it, and how far apart two are. */
    private static final int CAVE_BELOW_GROUND = 8, CAVE_UNDER_GROUND = 4, CAVE_GAP = 8;
    /** Cave heights stay this far above the bottom of the world (its bedrock). */
    private static final int CAVE_ABOVE_BOTTOM = 4;
    /** How far round a pocket's first hits its middle is looked for, and the step of that finer grid. */
    private static final int POCKET_REACH = 40, POCKET_STEP = 8;

    private NodeCaves() {
    }

    /**
     * A sulfur node, if a sulfur cave pocket lies under this cell's land. Looks on a coarse grid (no pocket of
     * radius 40 or more can slip between its points) at heights below the cell's ground and at the heights of an
     * ordinary world's caves, then finds the middle of the pocket. The cell that holds the middle owns the node.
     */
    static @Nullable Node sulfur(ServerLevel level, int cellX, int cellZ, int[] from) {
        int baseX = cellX * NodeMap.CELL, baseZ = cellZ * NodeMap.CELL;
        int[] offsets = {24, 64, 104};
        Integer ground = null;
        int[] heights = null;
        List<int[]> hits = new ArrayList<>();
        for (int ox : offsets) {
            for (int oz : offsets) {
                int x = baseX + ox, z = baseZ + oz;
                if (NodeLand.quickVoid(level, x, z)) continue;
                if (ground == null) {
                    Terrain.Column column = Terrain.column(level, x, z);
                    if (column == null) continue;
                    ground = column.y();
                    heights = caveHeights(level, ground);
                }
                for (int y : heights) {
                    NodeMap.SAMPLES.incrementAndGet();
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
        for (int dx = -POCKET_REACH; dx <= POCKET_REACH; dx += POCKET_STEP) {
            for (int dz = -POCKET_REACH; dz <= POCKET_REACH; dz += POCKET_STEP) {
                NodeMap.SAMPLES.incrementAndGet();
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
        if (Math.floorDiv(cx, NodeMap.CELL) != cellX || Math.floorDiv(cz, NodeMap.CELL) != cellZ) return null;
        cx = Math.clamp(cx, baseX + NodeMap.MARGIN, baseX + NodeMap.CELL - NodeMap.MARGIN - 1);
        cz = Math.clamp(cz, baseZ + NodeMap.MARGIN, baseZ + NodeMap.CELL - NodeMap.MARGIN - 1);
        long h = NodeMap.mix(level.getSeed() ^ NodeMap.mix(cellX * 0xD6E8FEB86659FD93L + cellZ * 0xA0761D6478BD642FL + 0x5F1F));
        double distance = Math.sqrt(Math.pow(cx - from[0], 2) + Math.pow(cz - from[1], 2));
        return new Node(cx, cz, NodeType.SULFUR, NodeRules.purity(h, distance), y);
    }

    private static int[] caveHeights(ServerLevel level, int ground) {
        int min = level.getMinY() + CAVE_ABOVE_BOTTOM;
        List<Integer> heights = new ArrayList<>();
        for (int y = ground - CAVE_BELOW_GROUND; y >= Math.max(min, ground - CAVE_DEPTH); y -= CAVE_STEP) heights.add(y);
        for (int y : CAVE_HEIGHTS) {
            if (y >= min && y < ground - CAVE_UNDER_GROUND && heights.stream().noneMatch(o -> Math.abs(o - y) < CAVE_GAP))
                heights.add(y);
        }
        return heights.stream().mapToInt(Integer::intValue).toArray();
    }
}
