package com.meakaandre.siftec.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jspecify.annotations.Nullable;

/** Asking about a position or chunk without loading it. */
public final class Chunks {
    private Chunks() {
    }

    /** The block entity at {@code pos}, or null when there is none or its chunk is not loaded (never loads it). */
    public static @Nullable BlockEntity blockEntity(Level level, BlockPos pos) {
        return level.isLoaded(pos) ? level.getBlockEntity(pos) : null;
    }

    /** True if the chunk {@code pos} is in is loaded and ticks its blocks. */
    public static boolean ticking(Level level, BlockPos pos) {
        return level.isLoaded(pos) && level.shouldTickBlocksAt(pos);
    }

    /** True if the chunk with this packed position ({@link ChunkPos#pack}) is loaded. */
    public static boolean loaded(Level level, long chunk) {
        return level.hasChunk(ChunkPos.getX(chunk), ChunkPos.getZ(chunk));
    }
}
