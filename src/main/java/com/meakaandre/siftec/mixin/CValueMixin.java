package com.meakaandre.siftec.mixin;

import com.meakaandre.siftec.tweak.SpeedCap;
import com.zurrtum.create.catnip.config.ConfigBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Create's maximum rotation speed setting is answered by the pack's speed cap. */
@Mixin(value = ConfigBase.CValue.class, remap = false)
public abstract class CValueMixin {
    @Shadow
    protected String name;

    @Inject(method = "get", at = @At("HEAD"), cancellable = true)
    private void siftec$speedCap(CallbackInfoReturnable<Object> cir) {
        if ("maxRotationSpeed".equals(name)) cir.setReturnValue(SpeedCap.value);
    }
}
