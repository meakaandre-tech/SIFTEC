package com.meakaandre.siftec.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.meakaandre.siftec.client.BackpackDraw;
import net.minecraft.client.gui.navigation.ScreenPosition;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The recipe book button stays on the crafting grid when a window moves up to make room for backpack rows. */
@Mixin(AbstractRecipeBookScreen.class)
public abstract class RecipeBookBackpackMixin {
    @WrapOperation(method = "*", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/screens/inventory/AbstractRecipeBookScreen;getRecipeBookButtonPosition()Lnet/minecraft/client/gui/navigation/ScreenPosition;"))
    private ScreenPosition siftec$followWindow(AbstractRecipeBookScreen<?> screen, Operation<ScreenPosition> original) {
        ScreenPosition position = original.call(screen);
        int lift = screen instanceof BackpackDraw.Window window ? window.siftec$lift() : 0;
        return lift == 0 ? position : new ScreenPosition(position.x(), position.y() - lift);
    }
}
