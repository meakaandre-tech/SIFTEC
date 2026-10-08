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
    // locals by type and order, not by name, so this works on a Create Fly built without local variable names:
    // booleans: incompatible (0), tooFast (1); block entities: currentTE (0, the parameter), neighbourTE (1);
    // floats: speedOfCurrent (0), speedOfNeighbour (1), newSpeed (2), oppositeSpeed (3)
    @ModifyVariable(method = "propagateNewSource", at = @At("STORE"), ordinal = 1)
    private static boolean siftec$companyLimit(boolean tooFast, KineticBlockEntity currentTE, @Local(ordinal = 1) KineticBlockEntity neighbourTE,
                                               @Local(ordinal = 2) float newSpeed, @Local(ordinal = 3) float oppositeSpeed) {
        return tooFast || SpeedCap.tooFast(currentTE, neighbourTE, newSpeed, oppositeSpeed);
    }

    /** A Speed Governor hands on exactly the speed it is set to, and takes exactly its own speed back. */
    @org.spongepowered.asm.mixin.injection.Inject(method = "getConveyedSpeed", at = @At("RETURN"), cancellable = true)
    private static void siftec$governor(KineticBlockEntity from, KineticBlockEntity to, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Float> cir) {
        float worked = cir.getReturnValueF();
        if (from instanceof com.meakaandre.siftec.governor.GovernorBlockEntity governor) cir.setReturnValue(governor.exact(from, to, worked));
        else if (to instanceof com.meakaandre.siftec.governor.GovernorBlockEntity governor) cir.setReturnValue(governor.exact(from, to, worked));
    }
}
