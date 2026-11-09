package com.meakaandre.siftec.selftest.mixin;

import com.meakaandre.siftec.client.RenderProbe;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.resources.model.geometry.ItemQuads;
import net.minecraft.world.item.ItemDisplayContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The render probe: an item reaching the frame's queue (see {@link RenderProbe}). */
@Mixin(SubmitNodeCollection.class)
public abstract class ItemQueueProbeMixin {
    @Inject(method = "submitItem", at = @At("HEAD"))
    private void siftecSelftest$queued(PoseStack ms, ItemDisplayContext context, int light, int overlay, int outline, int[] tints, ItemQuads quads,
                                       ItemStackRenderState.FoilType foil, CallbackInfo ci) {
        RenderProbe.itemQueued(ms, quads.all().size());
    }
}
