package com.meakaandre.siftec.selftest.mixin;

import com.meakaandre.siftec.client.RenderProbe;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The render probe: who submits an item to be drawn, where, how lit (see {@link RenderProbe}). */
@Mixin(ItemStackRenderState.class)
public abstract class ItemSubmitProbeMixin {
    @Inject(method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V", at = @At("HEAD"))
    private void siftecSelftest$submitted(PoseStack ms, SubmitNodeCollector collector, int light, int overlay, int outline, CallbackInfo ci) {
        RenderProbe.itemSubmitted(ms, light);
    }
}
