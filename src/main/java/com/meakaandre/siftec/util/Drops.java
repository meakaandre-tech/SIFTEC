package com.meakaandre.siftec.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Dropping items into the world at a block. */
public final class Drops {
    private Drops() {
    }

    /** Drops the stack over the middle of the block at {@code at}, {@code dy} above its bottom. */
    public static void at(Level level, BlockPos at, double dy, ItemStack stack) {
        Containers.dropItemStack(level, at.getX() + 0.5, at.getY() + dy, at.getZ() + 0.5, stack);
    }

    /** Drops each stack as {@link #at} does. */
    public static void all(Level level, BlockPos at, double dy, Iterable<ItemStack> stacks) {
        for (ItemStack stack : stacks) at(level, at, dy, stack);
    }
}
