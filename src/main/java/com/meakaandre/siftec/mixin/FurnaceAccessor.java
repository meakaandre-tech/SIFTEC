package com.meakaandre.siftec.mixin;

import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** How long a furnace's fire still burns, in ticks: what a Furnace Engine runs on (the block's "lit" look can be wrong). */
@Mixin(AbstractFurnaceBlockEntity.class)
public interface FurnaceAccessor {
    @Accessor("litTimeRemaining")
    int siftec$litTimeRemaining();
}
