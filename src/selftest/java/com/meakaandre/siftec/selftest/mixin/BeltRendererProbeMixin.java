package com.meakaandre.siftec.selftest.mixin;

import com.meakaandre.siftec.client.RenderProbe;
import com.zurrtum.create.client.content.kinetics.belt.BeltRenderer;
import com.zurrtum.create.content.kinetics.belt.BeltBlockEntity;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The render probe: which belt blocks Create's belt renderer is asked about and extracts (see {@link RenderProbe}). */
@Mixin(value = BeltRenderer.class, remap = false)
public abstract class BeltRendererProbeMixin {
    @Inject(method = "shouldRender(Lcom/zurrtum/create/content/kinetics/belt/BeltBlockEntity;Lnet/minecraft/world/phys/Vec3;)Z", at = @At("RETURN"))
    private void siftecSelftest$asked(BeltBlockEntity be, Vec3 camera, CallbackInfoReturnable<Boolean> cir) {
        RenderProbe.belt("asked " + cir.getReturnValue(), be.isController());
    }

    @Inject(method = "extractRenderState(Lcom/zurrtum/create/content/kinetics/belt/BeltBlockEntity;Lcom/zurrtum/create/client/content/kinetics/belt/BeltRenderer$BeltRenderState;FLnet/minecraft/world/phys/Vec3;Lnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)V",
        at = @At("HEAD"))
    private void siftecSelftest$extracted(BeltBlockEntity be, BeltRenderer.BeltRenderState state, float partialTicks, Vec3 camera,
                                          ModelFeatureRenderer.CrumblingOverlay crumbling, CallbackInfo ci) {
        RenderProbe.belt("extracted", be.isController());
    }
}
