package com.meakaandre.siftec.mixin;

import com.zurrtum.create.content.kinetics.RotationPropagator;
import com.zurrtum.create.content.kinetics.base.KineticBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The Speed Governor hands on exactly the speed it is set to. */
@Mixin(value = RotationPropagator.class, remap = false)
public abstract class PropagatorMixin {
    /** A Speed Governor hands on exactly the speed it is set to, and takes exactly its own speed back. */
    @org.spongepowered.asm.mixin.injection.Inject(method = "getConveyedSpeed", at = @At("RETURN"), cancellable = true)
    private static void siftec$governor(KineticBlockEntity from, KineticBlockEntity to, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Float> cir) {
        float worked = cir.getReturnValueF();
        if (from instanceof com.meakaandre.siftec.governor.GovernorBlockEntity governor) cir.setReturnValue(governor.exact(from, to, worked));
        else if (to instanceof com.meakaandre.siftec.governor.GovernorBlockEntity governor) cir.setReturnValue(governor.exact(from, to, worked));
    }
}
