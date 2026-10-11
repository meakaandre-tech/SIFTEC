package com.meakaandre.siftec.prefab;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The blocks of a structure, facing north (it stretches away from the player toward -Z), with x to the right,
 * y up and z away from the front edge. Each block is there from one level to another (HUB upgrades, wormhole
 * phases); for one spot, what was set last wins. Air marks inside space that is cleared when the structure goes
 * down.
 */
public final class Template {
    public record Entry(int from, int to, BlockState state) {
    }

    public static final BlockState AIR = Blocks.AIR.defaultBlockState();
    public final int maxLevel;
    private final Map<Long, List<Entry>> cells = new LinkedHashMap<>();
    public int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
    private List<Long> keys;
    private List<long[]> support;

    public Template(int maxLevel) {
        this.maxLevel = maxLevel;
    }

    public static long key(int x, int y, int z) {
        return BlockPos.asLong(x, y, z);
    }

    public void set(int from, int to, int x, int y, int z, BlockState state) {
        cells.computeIfAbsent(key(x, y, z), k -> new ArrayList<>()).add(new Entry(from, to, state));
        minX = Math.min(minX, x);
        maxX = Math.max(maxX, x);
        minY = Math.min(minY, y);
        maxY = Math.max(maxY, y);
        minZ = Math.min(minZ, z);
        maxZ = Math.max(maxZ, z);
        keys = null;
    }

    public void box(int from, int to, int x0, int y0, int z0, int x1, int y1, int z1, BlockState state) {
        for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++)
            for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++)
                for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) set(from, to, x, y, z, state);
    }

    /** The four sides of a box, one block thick, from y0 to y1. */
    public void ring(int from, int to, int x0, int y0, int z0, int x1, int y1, int z1, BlockState state) {
        box(from, to, x0, y0, z0, x1, y1, z0, state);
        box(from, to, x0, y0, z1, x1, y1, z1, state);
        box(from, to, x0, y0, z0, x0, y1, z1, state);
        box(from, to, x1, y0, z0, x1, y1, z1, state);
    }

    /** Every spot the structure ever uses, at any level. */
    public List<Long> keys() {
        if (keys == null) keys = Collections.unmodifiableList(new ArrayList<>(cells.keySet()));
        return keys;
    }

    /** What stands at a spot at a level: a block, air (inside space), or null (not part of the structure then). */
    public BlockState at(long key, int level) {
        List<Entry> list = cells.get(key);
        if (list == null) return null;
        for (int i = list.size() - 1; i >= 0; i--) {
            Entry e = list.get(i);
            if (level >= e.from && level <= e.to) return e.state;
        }
        return null;
    }

    /** True if something solid (not air) stands at the spot at any level. */
    public boolean solidAtAnyLevel(long key) {
        for (int level = 0; level <= maxLevel; level++) {
            BlockState state = at(key, level);
            if (state != null && !state.isAir()) return true;
        }
        return false;
    }

    /** The columns (x, z) with a block on the bottom row: these have to stand on ground, or on legs down to it. */
    public List<long[]> support() {
        if (support == null) {
            Set<Long> seen = new LinkedHashSet<>();
            List<long[]> out = new ArrayList<>();
            for (long key : keys()) {
                BlockPos p = BlockPos.of(key);
                if (p.getY() != minY || !solidAtAnyLevel(key)) continue;
                if (seen.add(BlockPos.asLong(p.getX(), 0, p.getZ()))) out.add(new long[]{p.getX(), p.getZ()});
            }
            support = out;
        }
        return support;
    }

    public int width() {
        return maxX - minX + 1;
    }

    public int length() {
        return maxZ - minZ + 1;
    }

    public int height() {
        return maxY - minY + 1;
    }

    /** Index of a column in a per-column array (legs). */
    public int column(int x, int z) {
        return (x - minX) * length() + (z - minZ);
    }
}
