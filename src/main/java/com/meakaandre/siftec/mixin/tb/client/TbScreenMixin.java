package com.meakaandre.siftec.mixin.tb.client;

import com.meakaandre.siftec.compat.TbClient;
import com.meakaandre.siftec.compat.TbEquipSlots;
import com.tiviacz.travelersbackpack.client.screens.AbstractBackpackScreen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The backpack window draws the panel behind its armour and offhand column, and a click on that panel counts as a
 * click in the window (not outside it, which would drop what the cursor holds).
 */
@Pseudo
@Mixin(targets = "com.tiviacz.travelersbackpack.client.screens.BackpackScreen", remap = false)
public abstract class TbScreenMixin {
    @Inject(method = "renderScreen(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIIIF)V", at = @At("TAIL"))
    private void siftec$equipment(GuiGraphicsExtractor g, int x, int y, int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        AbstractBackpackScreen<?> self = (AbstractBackpackScreen<?>) (Object) this;
        TbClient.drawEquipment(g, self.getMenu(), self.getGuiLeft(), self.getGuiTop());
    }

    @Inject(method = "hasClickedOutside(DDII)Z", at = @At("HEAD"), cancellable = true)
    private void siftec$inPanel(double mouseX, double mouseY, int left, int top, CallbackInfoReturnable<Boolean> cir) {
        AbstractBackpackScreen<?> self = (AbstractBackpackScreen<?>) (Object) this;
        if (TbEquipSlots.shown(self.getMenu()) && TbEquipSlots.inPanel(mouseX - left, mouseY - top)) cir.setReturnValue(false);
    }
}
