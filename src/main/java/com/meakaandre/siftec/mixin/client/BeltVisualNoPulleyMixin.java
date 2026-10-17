package com.meakaandre.siftec.mixin.client;

import com.zurrtum.create.client.content.kinetics.belt.BeltVisual;
import com.zurrtum.create.content.kinetics.belt.BeltBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Belts have no shafts: no pulley is drawn at a belt's ends (Flywheel's instanced path). */
@Mixin(value = BeltVisual.class, remap = false)
public abstract class BeltVisualNoPulleyMixin {
    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lcom/zurrtum/create/content/kinetics/belt/BeltBlockEntity;hasPulley()Z"))
    private boolean siftec$noPulley(BeltBlockEntity belt) {
        return false;
    }
}
