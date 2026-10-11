package com.meakaandre.siftec.mixin.tb;

import com.meakaandre.siftec.compat.TravelersBackpackCompat;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The Traveler's Backpack is bound to its player: the unequip button and key, and equipping another backpack over it
 * (which would hand the worn one back as an item), are refused.
 */
@Pseudo
@Mixin(targets = "com.tiviacz.travelersbackpack.common.ServerActions", remap = false)
public abstract class TbServerActionsMixin {
    @Inject(method = "unequipBackpack(Lnet/minecraft/world/entity/player/Player;)Z", at = @At("HEAD"), cancellable = true)
    private static void siftec$bound(Player player, CallbackInfoReturnable<Boolean> cir) {
        TravelersBackpackCompat.refuseUnequip(player);
        cir.setReturnValue(false);
    }

    @Inject(method = "swapBackpack(Lnet/minecraft/world/entity/player/Player;)Z", at = @At("HEAD"), cancellable = true)
    private static void siftec$noSwap(Player player, CallbackInfoReturnable<Boolean> cir) {
        TravelersBackpackCompat.refuseUnequip(player);
        cir.setReturnValue(false);
    }
}
