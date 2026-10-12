package com.meakaandre.siftec.mixin.client;

import com.meakaandre.siftec.client.BuildGunClient;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Ctrl + Scroll with the Build Gun picks a building instead of moving the hotbar. */
@Mixin(MouseHandler.class)
public abstract class BuildGunScrollMixin {
    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void siftec$buildGunScroll(long window, double horizontal, double vertical, CallbackInfo ci) {
        if (BuildGunClient.scroll(vertical)) ci.cancel();
    }
}
