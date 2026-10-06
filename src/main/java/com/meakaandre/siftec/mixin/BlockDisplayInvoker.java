package com.meakaandre.siftec.mixin;

import net.minecraft.world.entity.Display;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Lets a drone show as a block. */
@Mixin(Display.BlockDisplay.class)
public interface BlockDisplayInvoker {
    @Invoker("setBlockState")
    void siftec$show(BlockState state);
}
