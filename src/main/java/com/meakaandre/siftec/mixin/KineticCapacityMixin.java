package com.meakaandre.siftec.mixin;

import com.zurrtum.create.content.contraptions.bearing.WindmillBearingBlockEntity;
import com.zurrtum.create.content.kinetics.base.KineticBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Windmills give twice the stress capacity they do in Create. */
@Mixin(value = KineticBlockEntity.class, remap = false)
public abstract class KineticCapacityMixin {
    @Shadow
    protected float lastCapacityProvided;

    @Shadow
    protected float lastStressApplied;

    /** Power Shards and a Somersloop make a Create machine ask for more stress. */
    @Inject(method = "calculateStressApplied", at = @At("RETURN"), cancellable = true)
    private void siftec$boostStress(CallbackInfoReturnable<Float> cir) {
        float factor = com.meakaandre.siftec.owner.Boosts.stressFactor((KineticBlockEntity) (Object) this);
        if (factor != 1f) {
            float boosted = cir.getReturnValue() * factor;
            lastStressApplied = boosted;
            cir.setReturnValue(boosted);
        }
    }

    @Inject(method = "calculateAddedStressCapacity", at = @At("RETURN"), cancellable = true)
    private void siftec$doubleWindmills(CallbackInfoReturnable<Float> cir) {
        if ((Object) this instanceof WindmillBearingBlockEntity) {
            float doubled = cir.getReturnValue() * 2f;
            lastCapacityProvided = doubled;
            cir.setReturnValue(doubled);
        }
    }
}
