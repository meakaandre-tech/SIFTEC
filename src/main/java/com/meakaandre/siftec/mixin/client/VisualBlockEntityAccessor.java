package com.meakaandre.siftec.mixin.client;

import com.zurrtum.create.client.flywheel.lib.visual.AbstractBlockEntityVisual;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The block entity a Flywheel block entity visual draws (for {@link MixerVisualMixin}). */
@Mixin(value = AbstractBlockEntityVisual.class, remap = false)
public interface VisualBlockEntityAccessor {
    @Accessor("blockEntity")
    BlockEntity siftec$blockEntity();
}
