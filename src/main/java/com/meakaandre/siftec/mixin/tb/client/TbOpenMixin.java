package com.meakaandre.siftec.mixin.tb.client;

import com.meakaandre.siftec.compat.TbClient;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The backpack is the inventory: when the game would show the vanilla inventory (E) in survival or adventure, the
 * backpack window opens instead ({@link TbClient#openBackpackInstead}). Creative keeps the vanilla creative inventory.
 */
@Mixin(Gui.class)
public abstract class TbOpenMixin {
    @Inject(method = "setScreen(Lnet/minecraft/client/gui/screens/Screen;)V", at = @At("HEAD"), cancellable = true)
    private void siftec$backpackInstead(Screen screen, CallbackInfo ci) {
        if (TbClient.openBackpackInstead(screen)) ci.cancel();
    }
}
