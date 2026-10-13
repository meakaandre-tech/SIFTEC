package com.meakaandre.siftec.power;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The loaded wire connectors on a grid that has power right now (poles, towers, buildings), as the grids last saw
 * them (once a second). The Hover Pack only flies near one.
 */
public final class Poles {
    private static final Map<ResourceKey<Level>, Set<BlockPos>> LIVE = new ConcurrentHashMap<>();

    private Poles() {
    }

    static void set(Level level, Set<BlockPos> live) {
        Set<BlockPos> copy = ConcurrentHashMap.newKeySet();
        copy.addAll(live);
        LIVE.put(level.dimension(), copy);
    }

    public static boolean near(Level level, double x, double y, double z, double range) {
        Set<BlockPos> set = LIVE.get(level.dimension());
        if (set == null) return false;
        for (BlockPos pos : set) {
            double dx = pos.getX() + 0.5 - x, dy = pos.getY() + 0.5 - y, dz = pos.getZ() + 0.5 - z;
            if (dx * dx + dz * dz <= range * range && Math.abs(dy) <= range * 2) return true;
        }
        return false;
    }

    public static void clear() {
        LIVE.clear();
    }
}
