package com.meakaandre.siftec.mixin.client;

import com.meakaandre.siftec.client.BuildGunClient;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** In the Build Gun's nudge mode W, A, S and D move the hologram, so the player stands still. */
@Mixin(KeyboardInput.class)
public abstract class BuildGunNudgeMixin extends ClientInput {
    @Inject(method = "tick", at = @At("TAIL"))
    private void siftec$standStill(CallbackInfo ci) {
        if (!BuildGunClient.nudging()) return;
        this.keyPresses = new Input(false, false, false, false, false, this.keyPresses.shift(), false);
        this.moveVector = Vec2.ZERO;
    }
}
