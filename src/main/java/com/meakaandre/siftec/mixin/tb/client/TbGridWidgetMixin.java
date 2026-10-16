package com.meakaandre.siftec.mixin.tb.client;

import com.meakaandre.siftec.compat.TravelersBackpackCompat;
import com.tiviacz.travelersbackpack.client.screens.widgets.UpgradeWidgetBase;
import com.tiviacz.travelersbackpack.client.screens.widgets.WidgetBase;
import com.tiviacz.travelersbackpack.inventory.upgrades.UpgradeBase;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The built-in crafting grid's tab covers the right column and bottom row of the 3x3 it is drawn with, so it shows
 * the 2x2 it is (mixin/tb/TbCraftingGridMixin switches those slots off).
 */
@Pseudo
@Mixin(targets = "com.tiviacz.travelersbackpack.inventory.upgrades.crafting.CraftingWidget", remap = false)
public abstract class TbGridWidgetMixin {
    @Inject(method = "renderBg(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIII)V", at = @At("TAIL"))
    private void siftec$twoByTwo(GuiGraphicsExtractor g, int x, int y, int mouseX, int mouseY, CallbackInfo ci) {
        UpgradeWidgetBase<?> self = (UpgradeWidgetBase<?>) (Object) this;
        UpgradeBase<?> upgrade = self.getUpgrade();
        if (upgrade == null || !self.isTabOpened() || !TravelersBackpackCompat.isGrid(upgrade.getDataHolderStack())) return;
        int left = ((WidgetBase<?>) (Object) this).getPos().x(), top = ((WidgetBase<?>) (Object) this).getPos().y();
        // the grid's slots sit at (7 + column * 18, 23 + row * 18) in the tab; their frames one pixel round that
        g.fill(left + 6 + 2 * 18, top + 22, left + 6 + 3 * 18, top + 22 + 3 * 18, 0xFFC6C6C6);
        g.fill(left + 6, top + 22 + 2 * 18, left + 6 + 3 * 18, top + 22 + 3 * 18, 0xFFC6C6C6);
    }
}
