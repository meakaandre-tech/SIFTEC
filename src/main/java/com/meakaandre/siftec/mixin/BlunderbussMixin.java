package com.meakaandre.siftec.mixin;

import com.meakaandre.siftec.compat.ToxicShot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.LinkedHashSet;
import java.util.Set;

/** Create: Gunsmithing's Blunderbuss Barrel takes the pack's Toxic Shot as well as paper shot. */
@Pseudo
@Mixin(targets = "com.nukateam.cgs.common.ntgl.AttachmentMods$BlunderbussBarrel", remap = false)
public abstract class BlunderbussMixin {
    @Inject(method = "modifyAmmoItems", at = @At("RETURN"), cancellable = true)
    private void siftec$toxicShot(CallbackInfoReturnable<Set<Object>> cir) {
        Object toxic = ToxicShot.ammoType();
        if (toxic == null) return;
        Set<Object> ammo = new LinkedHashSet<>(cir.getReturnValue());
        ammo.add(toxic);
        cir.setReturnValue(ammo);
    }
}
