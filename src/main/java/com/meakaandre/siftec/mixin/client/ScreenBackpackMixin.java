package com.meakaandre.siftec.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.meakaandre.siftec.client.BackpackDraw;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Container screens with the backpack draw their background in slices (see {@link BackpackDraw}) and grow after their init. */
@Mixin(Screen.class)
public abstract class ScreenBackpackMixin {
    @WrapOperation(method = "extractRenderStateWithTooltipAndSubtitles", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/screens/Screen;extractBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V"))
    private void siftec$withBackpackRows(Screen screen, GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, Operation<Void> original) {
        if (BackpackDraw.pass == 0 && screen instanceof BackpackDraw.Window window && (window.siftec$extra() > 0 || window.siftec$wide() > 0)) {
            BackpackDraw.draw(screen, window, graphics, mouseX, mouseY, delta, original);
        } else {
            original.call(screen, graphics, mouseX, mouseY, delta);
        }
    }

    /** After the whole init of a container screen (its own class's part too, which runs after the base class's). */
    @Inject(method = {"init(II)V", "rebuildWidgets()V"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/Screen;init()V", shift = At.Shift.AFTER))
    private void siftec$afterInit(CallbackInfo ci) {
        if ((Object) this instanceof BackpackDraw.Window window) window.siftec$afterInit();
    }

    @Inject(method = "extractBackground", at = @At("HEAD"), cancellable = true)
    private void siftec$backdropOnce(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (BackpackDraw.pass != 0) BackpackDraw.backdropStart(graphics, ci);
    }

    @Inject(method = "extractBackground", at = @At("RETURN"))
    private void siftec$backdropDone(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (BackpackDraw.pass != 0) BackpackDraw.backdropEnd(graphics);
    }
}
