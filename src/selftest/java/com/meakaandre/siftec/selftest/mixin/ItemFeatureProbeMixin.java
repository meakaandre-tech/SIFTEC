package com.meakaandre.siftec.selftest.mixin;

import com.meakaandre.siftec.client.RenderProbe;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The render probe: an item the item feature renderer prepares to draw (see {@link RenderProbe}). */
@Mixin(ItemFeatureRenderer.class)
public abstract class ItemFeatureProbeMixin {
    @Inject(method = "prepareMainSubmit", at = @At("HEAD"))
    private void siftecSelftest$prepared(ItemFeatureRenderer.Submit submit, CallbackInfo ci) {
        if (RenderProbe.on()) RenderProbe.itemPrepared(submit.pose().pose(), submit.lightCoords(), submit.quads().size(), String.valueOf(submit.displayContext()));
    }
}
