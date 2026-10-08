package com.meakaandre.siftec.mixin.client;

import com.meakaandre.siftec.backpack.Backpack;
import com.meakaandre.siftec.backpack.BackpackSlot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The backpack panel hangs below the 166-pixel inventory window. Without this, a click on it counts as a click
 * outside the window: the game throws the held item, or the item under the cursor, on the ground.
 */
@Mixin(AbstractRecipeBookScreen.class)
public abstract class BackpackClickMixin {
    @Inject(method = "hasClickedOutside", at = @At("RETURN"), cancellable = true)
    private void siftec$backpackInside(double mouseX, double mouseY, int left, int top, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() || !((Object) this instanceof InventoryScreen screen) || Minecraft.getInstance().player == null) return;
        int open = Backpack.unlocked(Minecraft.getInstance().player);
        int rows = (open + 8) / 9;
        if (rows > 0 && mouseX >= left && mouseX < left + 176 && mouseY >= top + 166 && mouseY < top + BackpackSlot.TOP + rows * 18 + 6) {
            cir.setReturnValue(false);
            return;
        }
        // an item left in a slot that is no longer open still shows, and can be taken out
        for (Slot slot : screen.getMenu().slots) {
            if (slot instanceof BackpackSlot && slot.isActive() && mouseX >= left + slot.x - 1 && mouseX < left + slot.x + 17
                && mouseY >= top + slot.y - 1 && mouseY < top + slot.y + 17) {
                cir.setReturnValue(false);
                return;
            }
        }
    }
}
