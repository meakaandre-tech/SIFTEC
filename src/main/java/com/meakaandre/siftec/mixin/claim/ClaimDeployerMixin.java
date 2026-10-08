package com.meakaandre.siftec.mixin.claim;

import com.meakaandre.siftec.claim.Claims;
import com.zurrtum.create.content.kinetics.deployer.DeployerBlockEntity;
import com.zurrtum.create.content.kinetics.deployer.DeployerHandler;
import com.zurrtum.create.content.kinetics.deployer.DeployerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A Deployer acts as its owner (Create gives its fake player the owner's id), so inside a claim it may only
 * punch, use, place or attack when the owner's company holds the claim, or when it works inside that claim itself.
 */
@Mixin(DeployerHandler.class)
public abstract class ClaimDeployerMixin {
    @Inject(method = "activate", at = @At("HEAD"), cancellable = true)
    private static void siftec$claim(DeployerPlayer player, Vec3 vec, BlockPos clickedPos, Vec3 extensionVector, DeployerBlockEntity.Mode mode, CallbackInfo ci) {
        ServerPlayer fake = player.cast();
        if (!Claims.allowed(fake, fake.level(), clickedPos)) ci.cancel();
    }
}
