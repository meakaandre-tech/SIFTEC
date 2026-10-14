package com.meakaandre.siftec.mixin.building;

import com.zurrtum.create.content.processing.burner.BlazeBurnerBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A building keeps its Blaze Burner lit while it has power: the fuel it is burning and how long it has left. */
@Mixin(value = BlazeBurnerBlockEntity.class, remap = false)
public interface BlazeBurnerAccessor {
    @Accessor("activeFuel")
    void siftec$setActiveFuel(BlazeBurnerBlockEntity.FuelType fuel);

    @Accessor("remainingBurnTime")
    void siftec$setRemainingBurnTime(int ticks);
}
