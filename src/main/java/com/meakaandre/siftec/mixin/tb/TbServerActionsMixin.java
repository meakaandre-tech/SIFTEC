package com.meakaandre.siftec.mixin.tb;

import com.meakaandre.siftec.compat.TravelersBackpackCompat;
import com.tiviacz.travelersbackpack.inventory.menu.BackpackBaseMenu;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The Traveler's Backpack is the player's inventory and bound to them: the unequip button and key, and equipping
 * another backpack over it (which would hand the worn one back as an item), are refused. The built-in 2x2 crafting
 * grid cannot be taken out of its upgrade slot either.
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

    @Inject(method = "removeBackpackUpgrade(Lnet/minecraft/server/level/ServerPlayer;I)V", at = @At("HEAD"), cancellable = true)
    private static void siftec$keepGrid(ServerPlayer player, int slot, CallbackInfo ci) {
        if (!(player.containerMenu instanceof BackpackBaseMenu menu)) return;
        var upgrades = menu.getWrapper().getUpgrades();
        if (slot < 0 || slot >= upgrades.getSlots() || !TravelersBackpackCompat.isGrid(upgrades.getStackInSlot(slot))) return;
        TravelersBackpackCompat.mayRemoveUpgrade(player, slot);
        menu.sendAllDataToRemote();
        ci.cancel();
    }
}
