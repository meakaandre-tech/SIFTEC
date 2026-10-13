package com.meakaandre.siftec.mixin.building;

import com.meakaandre.siftec.building.BasinPace;
import com.zurrtum.create.content.kinetics.mixer.MechanicalMixerBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A Mixer goes on mixing the same recipe without starting again; in a building it only does so once its building's
 * last recipe is done (it winds down and looks again otherwise), and the building counts the new run.
 */
@Mixin(value = MechanicalMixerBlockEntity.class, remap = false)
public abstract class MixerPaceMixin {
    @Inject(method = "continueWithPreviousRecipe", at = @At("HEAD"), cancellable = true)
    private void siftec$wait(CallbackInfoReturnable<Boolean> cir) {
        MechanicalMixerBlockEntity self = (MechanicalMixerBlockEntity) (Object) this;
        if (BasinPace.blocked(self)) {
            cir.setReturnValue(false);
            return;
        }
        BasinPace.started(self);
    }
}
