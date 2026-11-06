package com.meakaandre.siftec.mixin.client;

import com.meakaandre.siftec.client.ShaftlessBeltPreview;
import com.zurrtum.create.client.content.kinetics.belt.item.BeltConnectorHandler;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Create's belt preview line, also for belts laid with no shafts (see {@link ShaftlessBeltPreview}). */
@Mixin(value = BeltConnectorHandler.class, remap = false)
public abstract class BeltConnectorPreviewMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private static void siftec$shaftlessPreview(Minecraft mc, CallbackInfo ci) {
        if (ShaftlessBeltPreview.tick(mc)) ci.cancel();
    }
}
