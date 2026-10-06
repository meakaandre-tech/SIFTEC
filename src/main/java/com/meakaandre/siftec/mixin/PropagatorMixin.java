package com.meakaandre.siftec.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.meakaandre.siftec.tweak.SpeedCap;
import com.zurrtum.create.content.kinetics.RotationPropagator;
import com.zurrtum.create.content.kinetics.base.KineticBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Create's "too fast, the block breaks" check also counts the speed limit of the company each block belongs to. */
@Mixin(value = RotationPropagator.class, remap = false)
public abstract class PropagatorMixin {
    @ModifyVariable(method = "propagateNewSource", at = @At("STORE"), name = "tooFast")
    private static boolean siftec$companyLimit(boolean tooFast, KineticBlockEntity currentTE, @Local(name = "neighbourTE") KineticBlockEntity neighbourTE,
                                               @Local(name = "newSpeed") float newSpeed, @Local(name = "oppositeSpeed") float oppositeSpeed) {
        return tooFast || SpeedCap.tooFast(currentTE, neighbourTE, newSpeed, oppositeSpeed);
    }
}
