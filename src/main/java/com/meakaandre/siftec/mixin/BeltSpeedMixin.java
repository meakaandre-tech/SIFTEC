package com.meakaandre.siftec.mixin;

import com.meakaandre.siftec.tweak.SelfMovingBelts;
import com.zurrtum.create.content.kinetics.base.KineticBlockEntity;
import com.zurrtum.create.content.kinetics.belt.BeltBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A belt with no shaft driving it moves at its company's self-moving speed. Only getSpeed (what belts, funnels,
 * tunnels and the renderer read) changes; the rotation network goes by the theoretical speed, which stays 0.
 */
@Mixin(value = KineticBlockEntity.class, remap = false)
public abstract class BeltSpeedMixin {
    @Inject(method = "getSpeed", at = @At("RETURN"), cancellable = true)
    private void siftec$selfMovingBelt(CallbackInfoReturnable<Float> cir) {
        if (cir.getReturnValueF() != 0 || !((Object) this instanceof BeltBlockEntity belt)) return;
        float self = SelfMovingBelts.speed(belt);
        if (self != 0) cir.setReturnValue(self);
    }
}
