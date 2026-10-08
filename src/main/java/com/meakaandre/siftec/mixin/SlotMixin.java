package com.meakaandre.siftec.mixin;

import com.meakaandre.siftec.hub.Locks;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A locked item can be seen in a result slot but not taken out: the crafting grids, and the Stonecutter's result
 * slot (an anonymous Slot that does not override this). The smithing table and anvil override it, see
 * {@link ItemCombinerResultMixin}.
 */
@Mixin(Slot.class)
public abstract class SlotMixin {
    @Shadow
    public abstract ItemStack getItem();

    @Inject(method = "mayPickup", at = @At("HEAD"), cancellable = true)
    private void siftec$lockedResult(Player player, CallbackInfoReturnable<Boolean> cir) {
        if (!((Object) this instanceof ResultSlot) && !"net.minecraft.world.inventory.StonecutterMenu$2".equals(getClass().getName())) return;
        if (!Locks.mayTake(player, getItem())) cir.setReturnValue(false);
    }
}
