package com.meakaandre.siftec.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.meakaandre.siftec.client.BackpackDraw;
import net.minecraft.client.gui.navigation.ScreenPosition;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The recipe book and its button with the backpack: the window is placed beside the book from its own width (it grows
 * to the right, away from the book), and the button stays on the crafting grid when the book is opened or closed in
 * a window that was moved up to stay on screen (during init the backpack code moves it with the other widgets).
 */
@Mixin(AbstractRecipeBookScreen.class)
public abstract class RecipeBookBackpackMixin {
    @WrapOperation(method = "*", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/screens/inventory/AbstractRecipeBookScreen;getRecipeBookButtonPosition()Lnet/minecraft/client/gui/navigation/ScreenPosition;"))
    private ScreenPosition siftec$followWindow(AbstractRecipeBookScreen<?> screen, Operation<ScreenPosition> original) {
        ScreenPosition position = original.call(screen);
        if (!(screen instanceof BackpackDraw.Window window) || window.siftec$initing()) return position;
        int lift = window.siftec$lift();
        return lift == 0 ? position : new ScreenPosition(position.x(), position.y() - lift);
    }

    @WrapOperation(method = "*", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/screens/recipebook/RecipeBookComponent;updateScreenPosition(II)I"))
    private int siftec$ownWidth(RecipeBookComponent<?> book, int width, int imageWidth, Operation<Integer> original) {
        if ((Object) this instanceof BackpackDraw.Window window) imageWidth = window.siftec$baseWidth();
        return original.call(book, width, imageWidth);
    }
}
