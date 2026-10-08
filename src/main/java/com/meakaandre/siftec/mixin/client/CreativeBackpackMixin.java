package com.meakaandre.siftec.mixin.client;

import com.meakaandre.siftec.backpack.BackpackSlot;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The creative inventory tab lays every slot past the main inventory onto the hotbar row, so the backpack slots
 * would be drawn over it. They are hidden while the creative screen is open (creative players have everything anyway).
 */
@Mixin(CreativeModeInventoryScreen.class)
public abstract class CreativeBackpackMixin {
    @Inject(method = "init", at = @At("HEAD"))
    private void siftec$hideBackpack(CallbackInfo ci) {
        BackpackSlot.hiddenOnClient = true;
    }

    @Inject(method = "removed", at = @At("HEAD"))
    private void siftec$showBackpack(CallbackInfo ci) {
        BackpackSlot.hiddenOnClient = false;
    }
}
