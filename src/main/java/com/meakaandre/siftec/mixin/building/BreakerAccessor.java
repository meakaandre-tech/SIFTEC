package com.meakaandre.siftec.mixin.building;

import com.zurrtum.create.content.kinetics.base.BlockBreakingKineticBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Whether a Drill is between blocks (-1: it waits for its next half-second check before it starts on a new one). */
@Mixin(value = BlockBreakingKineticBlockEntity.class, remap = false)
public interface BreakerAccessor {
    @Accessor("ticksUntilNextProgress")
    int siftec$ticksUntilNextProgress();
}
