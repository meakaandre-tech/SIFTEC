package com.meakaandre.siftec.mixin;

import com.meakaandre.siftec.tweak.TunnelSplitter;
import com.zurrtum.create.content.kinetics.belt.transport.BeltInventory;
import com.zurrtum.create.content.kinetics.belt.transport.BeltTunnelInteractionHandler;
import com.zurrtum.create.content.kinetics.belt.transport.TransportedItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Andesite Tunnels at a junction split evenly (see {@link TunnelSplitter}). */
@Mixin(value = BeltTunnelInteractionHandler.class, remap = false)
public abstract class TunnelMixin {
    @Inject(method = "flapTunnelsAndCheckIfStuck", at = @At("HEAD"), cancellable = true)
    private static void siftec$evenSplit(BeltInventory inventory, TransportedItemStack current, float nextOffset, CallbackInfoReturnable<Boolean> cir) {
        Boolean handled = TunnelSplitter.handle(inventory, current, nextOffset);
        if (handled != null) cir.setReturnValue(handled);
    }
}
