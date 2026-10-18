package com.meakaandre.siftec.mixin.client;

import com.meakaandre.siftec.client.CosmeticsClient;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A player playing an emote (AWESOME Shop) moves their arms through it. */
@Mixin(PlayerModel.class)
public abstract class PlayerEmoteMixin {
    @Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V", at = @At("TAIL"))
    private void siftec$emote(AvatarRenderState state, CallbackInfo ci) {
        CosmeticsClient.pose((PlayerModel) (Object) this, state);
    }
}
