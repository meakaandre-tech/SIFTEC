package com.meakaandre.siftec.power;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** The loaded Power Poles and Towers that are turning right now. The Hover Pack only flies near one. */
public final class Poles {
    private static final Map<ResourceKey<Level>, Set<BlockPos>> LIVE = new ConcurrentHashMap<>();

    private Poles() {
    }

    public static void mark(Level level, BlockPos pos, boolean turning) {
        Set<BlockPos> set = LIVE.computeIfAbsent(level.dimension(), k -> ConcurrentHashMap.newKeySet());
        if (turning) set.add(pos.immutable());
        else set.remove(pos);
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
