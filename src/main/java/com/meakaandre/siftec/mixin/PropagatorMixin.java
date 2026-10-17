package com.meakaandre.siftec.mixin;

import com.zurrtum.create.content.kinetics.RotationPropagator;
import com.zurrtum.create.content.kinetics.base.KineticBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The Speed Governor hands on exactly the speed it is set to; the HUB's Craft Station takes rotation only from its own
 * hand cranks (no shaft, motor or water wheel from outside turns it).
 */
@Mixin(value = RotationPropagator.class, remap = false)
public abstract class PropagatorMixin {
    /** A Speed Governor hands on exactly the speed it is set to, and takes exactly its own speed back. */
    @org.spongepowered.asm.mixin.injection.Inject(method = "getConveyedSpeed", at = @At("RETURN"), cancellable = true)
    private static void siftec$governor(KineticBlockEntity from, KineticBlockEntity to, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Float> cir) {
        float worked = cir.getReturnValueF();
        // no speed is passed between the HUB's Craft Station and anything outside it (see siftec$craftStation)
        if (worked != 0 && com.meakaandre.siftec.building.SpecialBuildings.cutsRotation(from, to)) {
            cir.setReturnValue(0f);
            return;
        }
        if (from instanceof com.meakaandre.siftec.governor.GovernorBlockEntity governor) cir.setReturnValue(governor.exact(from, to, worked));
        else if (to instanceof com.meakaandre.siftec.governor.GovernorBlockEntity governor) cir.setReturnValue(governor.exact(from, to, worked));
    }

    /**
     * The HUB's Craft Station and anything outside it are not kinetic neighbours: Create only passes rotation between
     * blocks it finds connected here (both ways round), so no shaft, motor or water wheel outside turns the station.
     */
    @org.spongepowered.asm.mixin.injection.Inject(method = "isConnected", at = @At("HEAD"), cancellable = true)
    private static void siftec$craftStation(KineticBlockEntity from, KineticBlockEntity to, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        if (com.meakaandre.siftec.building.SpecialBuildings.cutsRotation(from, to)) cir.setReturnValue(false);
    }
}
