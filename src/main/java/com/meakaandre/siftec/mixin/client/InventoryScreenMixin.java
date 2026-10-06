package com.meakaandre.siftec.mixin.client;

import com.meakaandre.siftec.backpack.Backpack;
import com.meakaandre.siftec.backpack.BackpackSlot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws the backpack panel under the inventory: a plain panel with one frame per open slot. */
@Mixin(InventoryScreen.class)
public abstract class InventoryScreenMixin {
    @Inject(method = "extractBackground", at = @At("TAIL"))
    private void siftec$backpackPanel(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (Minecraft.getInstance().player == null) return;
        int open = Backpack.unlocked(Minecraft.getInstance().player);
        if (open <= 0) return;
        int left = ((ScreenAccessor) this).siftec$left(), top = ((ScreenAccessor) this).siftec$top();
        int rows = (open + 8) / 9;
        int x0 = left, y0 = top + 166, x1 = left + 176, y1 = top + BackpackSlot.TOP + rows * 18 + 6;
        // the panel, in the colours of vanilla's inventory window
        graphics.fill(x0, y0, x1, y1, 0xFF000000);
        graphics.fill(x0 + 1, y0, x1 - 1, y1 - 1, 0xFFC6C6C6);
        graphics.fill(x0 + 1, y1 - 3, x1 - 1, y1 - 1, 0xFF555555);
        graphics.fill(x1 - 3, y0, x1 - 1, y1 - 1, 0xFF555555);
        graphics.fill(x0 + 1, y0, x0 + 3, y1 - 3, 0xFFFFFFFF);
        for (int i = 0; i < open; i++) {
            int x = left + BackpackSlot.LEFT + (i % 9) * 18 - 1, y = top + BackpackSlot.TOP + (i / 9) * 18 - 1;
            graphics.fill(x, y, x + 18, y + 18, 0xFFFFFFFF);
            graphics.fill(x, y, x + 17, y + 17, 0xFF373737);
            graphics.fill(x + 1, y + 1, x + 17, y + 17, 0xFF8B8B8B);
        }
    }
}
