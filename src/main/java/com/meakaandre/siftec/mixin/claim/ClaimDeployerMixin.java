package com.meakaandre.siftec.mixin.claim;

import com.meakaandre.siftec.building.BuildingProtection;
import com.meakaandre.siftec.claim.Claims;
import com.meakaandre.siftec.node.NodeSites;
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
 * It never acts on a Build Gun building's block ({@link BuildingProtection#deployerMayAct}): buildings come down only with the
 * Build Gun. A Deployer that is itself part of that building (the Manufacturer's line) still works on it. It never
 * punches an ore node's position (node.NodeSites).
 */
@Mixin(DeployerHandler.class)
public abstract class ClaimDeployerMixin {
    @Inject(method = "activate", at = @At("HEAD"), cancellable = true)
    private static void siftec$claim(DeployerPlayer player, Vec3 vec, BlockPos clickedPos, Vec3 extensionVector, DeployerBlockEntity.Mode mode, CallbackInfo ci) {
        ServerPlayer fake = player.cast();
        // vec is the middle of the Deployer's own block (or where it is on a moving contraption)
        if (!Claims.allowed(fake, fake.level(), clickedPos) || !BuildingProtection.deployerMayAct(fake.level(), BlockPos.containing(vec), clickedPos)) ci.cancel();
        // nor does it mine an ore node: a Deployer with a Fortune pickaxe would give a Miner Mk.2's output with no milestone
        // (plain Create drills may mine nodes)
        else if (mode == DeployerBlockEntity.Mode.PUNCH && NodeSites.isSite(fake.level(), clickedPos)) ci.cancel();
    }
}
