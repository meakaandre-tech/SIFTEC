package com.meakaandre.siftec.mixin;

import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Moves a slot on screen: the hotbar goes down when backpack rows are shown above it. */
@Mixin(Slot.class)
public interface SlotAccessor {
    @Mutable
    @Accessor("y")
    void siftec$setY(int y);
}
