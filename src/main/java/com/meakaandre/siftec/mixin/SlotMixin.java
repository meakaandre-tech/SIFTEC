package com.meakaandre.siftec.mixin;

import com.meakaandre.siftec.hub.Locks;
import com.meakaandre.siftec.hub.Milestone;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A locked item can be seen in the crafting result but not taken out. */
@Mixin(Slot.class)
public abstract class SlotMixin {
    @Shadow
    public abstract ItemStack getItem();

    @Inject(method = "mayPickup", at = @At("HEAD"), cancellable = true)
    private void siftec$lockedResult(Player player, CallbackInfoReturnable<Boolean> cir) {
        if (!((Object) this instanceof ResultSlot)) return;
        ItemStack stack = getItem();
        if (Locks.allowed(player, stack)) return;
        Milestone lock = Locks.lockOf(stack.getItem());
        if (player instanceof ServerPlayer server && lock != null) {
            server.sendOverlayMessage(Component.translatable("siftec.lock.item", lock.name()));
        }
        cir.setReturnValue(false);
    }
}
