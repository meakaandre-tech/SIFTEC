package com.meakaandre.siftec.mixin.tb;

import com.meakaandre.siftec.compat.TravelersBackpackCompat;
import com.tiviacz.travelersbackpack.inventory.menu.BackpackBaseMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A backpack upgrade the player's company has not unlocked cannot go into an upgrade slot. */
@Pseudo
@Mixin(targets = "com.tiviacz.travelersbackpack.inventory.menu.slot.UpgradeLockableSlotItemHandler", remap = false)
public abstract class TbUpgradeSlotMixin {
    @Shadow
    public BackpackBaseMenu menu;

    @Inject(method = "mayPlace(Lnet/minecraft/world/item/ItemStack;)Z", at = @At("HEAD"), cancellable = true)
    private void siftec$locked(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (menu != null && !TravelersBackpackCompat.mayUseUpgrade(menu.player, stack)) cir.setReturnValue(false);
    }
}
