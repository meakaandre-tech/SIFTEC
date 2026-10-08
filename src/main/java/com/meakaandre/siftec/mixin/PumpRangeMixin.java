package com.meakaandre.siftec.mixin;

import com.zurrtum.create.content.fluids.FluidPropagator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Pipeline Engineering Mk.2 doubles a pump's range. A changed pipe looks for the pumps it should wake up only as
 * far as the base range, so a tank 17 to 32 pipes away never woke an idle upgraded pump. The search now goes as far
 * as the longest range a pump can have; each pump it finds still works out its own reach.
 */
@Mixin(value = FluidPropagator.class, remap = false)
public abstract class PumpRangeMixin {
    @Redirect(method = "propagateChangedPipe", at = @At(value = "INVOKE", target = "Lcom/zurrtum/create/content/fluids/FluidPropagator;getPumpRange()I"))
    private static int siftec$longestRange() {
        return FluidPropagator.getPumpRange() * 2;
    }
}
