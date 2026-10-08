package com.meakaandre.siftec.mixin;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The smithing table's (and anvil's) result slot: a locked item, such as netherite diving gear, cannot be taken out. */
@Mixin(targets = "net.minecraft.world.inventory.ItemCombinerMenu$3")
public abstract class ItemCombinerResultMixin extends Slot {
    private ItemCombinerResultMixin(Container container, int slot, int x, int y) {
        super(container, slot, x, y);
    }

    @Inject(method = "mayPickup", at = @At("HEAD"), cancellable = true)
    private void siftec$lockedResult(Player player, CallbackInfoReturnable<Boolean> cir) {
        if (!com.meakaandre.siftec.hub.Locks.mayTake(player, getItem())) cir.setReturnValue(false);
    }
}
