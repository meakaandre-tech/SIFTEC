package com.meakaandre.siftec.mixin.power;

import org.antarcticgardens.cna.content.electricity.network.ElectricalNetwork;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * New Age's own way of moving energy along wires (each pulled source pushing down the shortest path, within each
 * wire's limit) is off: the pack's grids balance every wired network Satisfactory's way ({@link com.meakaandre.siftec.power.PowerGrids}).
 */
@Mixin(value = ElectricalNetwork.class, remap = false)
public abstract class NetworkTickMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void siftec$gridsInstead(CallbackInfo ci) {
        ci.cancel();
    }
}
