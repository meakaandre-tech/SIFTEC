package com.meakaandre.siftec.tweak;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pollution, for looks only. Anything burning fuel for power (HUB Engines, Furnace Engines, lit Blaze Burners)
 * sends up a column of smoke, and where several burn close together ash hangs in the air. Nothing is harmed.
 */
public final class Pollution {
    /** Burners that reported in lately: where, and the game time they last did. */
    private static final Map<ResourceKey<Level>, Map<BlockPos, Long>> BURNING = new ConcurrentHashMap<>();
    private static final int FRESH = 60, HAZE_RANGE = 48, HAZE_FROM = 4;

    private Pollution() {
    }

    /** Called about once a second by each thing that is burning fuel. */
    public static void burning(Level level, BlockPos pos) {
        if (level.isClientSide()) return;
        BURNING.computeIfAbsent(level.dimension(), k -> new ConcurrentHashMap<>()).put(pos.immutable(), level.getGameTime());
    }

    public static void clear() {
        BURNING.clear();
    }

    public static void tick(MinecraftServer server) {
        if (server.getTickCount() % 40 != 0) return;
        for (ServerLevel level : server.getAllLevels()) {
            Map<BlockPos, Long> burners = BURNING.get(level.dimension());
            if (burners == null || burners.isEmpty()) continue;
            long now = level.getGameTime();
            burners.values().removeIf(seen -> now - seen > FRESH);
            for (BlockPos pos : burners.keySet()) {
                // count 0 turns the three "spread" numbers into the direction the smoke drifts
                level.sendParticles(ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, pos.getX() + 0.5, pos.getY() + 1.3, pos.getZ() + 0.5, 0, 0.0, 0.07, 0.0, 1.0);
            }
            for (ServerPlayer player : level.players()) {
                int near = 0;
                for (BlockPos pos : burners.keySet()) {
                    if (pos.distSqr(player.blockPosition()) <= HAZE_RANGE * HAZE_RANGE) near++;
                }
                if (near < HAZE_FROM) continue;
                level.sendParticles(player, ParticleTypes.ASH, false, false, player.getX(), player.getY() + 3, player.getZ(), Math.min(80, near * 8), 8.0, 4.0, 8.0, 0.0);
            }
        }
    }
}
