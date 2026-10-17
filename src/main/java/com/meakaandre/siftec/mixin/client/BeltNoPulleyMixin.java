package com.meakaandre.siftec.mixin.client;

import com.zurrtum.create.client.content.kinetics.belt.BeltRenderer;
import com.zurrtum.create.content.kinetics.belt.BeltBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Belts have no shafts: no pulley is drawn at a belt's ends (the renderer's path). */
@Mixin(value = BeltRenderer.class, remap = false)
public abstract class BeltNoPulleyMixin {
    @Redirect(method = "extractRenderState(Lcom/zurrtum/create/content/kinetics/belt/BeltBlockEntity;Lcom/zurrtum/create/client/content/kinetics/belt/BeltRenderer$BeltRenderState;FLnet/minecraft/world/phys/Vec3;Lnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)V",
        at = @At(value = "INVOKE", target = "Lcom/zurrtum/create/content/kinetics/belt/BeltBlockEntity;hasPulley()Z"))
    private boolean siftec$noPulley(BeltBlockEntity belt) {
        return false;
    }
}
