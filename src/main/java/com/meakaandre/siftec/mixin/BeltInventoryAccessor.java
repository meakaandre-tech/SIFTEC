package com.meakaandre.siftec.mixin;

import com.zurrtum.create.content.kinetics.belt.BeltBlockEntity;
import com.zurrtum.create.content.kinetics.belt.transport.BeltInventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A belt inventory's belt and its direction of travel, for {@link com.meakaandre.siftec.tweak.TunnelSplitter}. */
@Mixin(value = BeltInventory.class, remap = false)
public interface BeltInventoryAccessor {
    @Accessor("belt")
    BeltBlockEntity siftec$belt();

    @Accessor("beltMovementPositive")
    boolean siftec$positive();
}
