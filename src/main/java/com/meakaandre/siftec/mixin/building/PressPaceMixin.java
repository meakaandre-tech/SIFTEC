package com.meakaandre.siftec.mixin.building;

import com.meakaandre.siftec.building.BasinPace;
import com.zurrtum.create.content.kinetics.press.MechanicalPressBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A Press in a building does not go straight on to the next recipe while its building is waiting: it checks again later. */
@Mixin(value = MechanicalPressBlockEntity.class, remap = false)
public abstract class PressPaceMixin {
    @Inject(method = "onPressingCompleted", at = @At("HEAD"), cancellable = true)
    private void siftec$wait(CallbackInfo ci) {
        MechanicalPressBlockEntity self = (MechanicalPressBlockEntity) (Object) this;
        if (!BasinPace.blocked(self)) return;
        self.basinChecker.scheduleUpdate();
        ci.cancel();
    }
}
