package com.meakaandre.siftec.mixin;

import com.meakaandre.siftec.compat.SiftGate;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The wormhole is the only way into The Sift: whatever tries to take a player there (the mod's own portal in
 * an Ancient City, a rift, a command) is refused until the player's company has finished Wormhole Phase 5.
 */
@Mixin(ServerPlayer.class)
public abstract class SiftEntryMixin {
    @Inject(method = "teleport(Lnet/minecraft/world/level/portal/TeleportTransition;)Lnet/minecraft/server/level/ServerPlayer;", at = @At("HEAD"), cancellable = true)
    private void siftec$gate(TeleportTransition transition, CallbackInfoReturnable<ServerPlayer> cir) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (SiftGate.refuses(self, transition.newLevel())) cir.setReturnValue(self);
    }
}
