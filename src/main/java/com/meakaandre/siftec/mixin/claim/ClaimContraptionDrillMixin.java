package com.meakaandre.siftec.mixin.claim;

import com.meakaandre.siftec.claim.Claims;
import com.zurrtum.create.content.contraptions.behaviour.MovementContext;
import com.zurrtum.create.content.kinetics.base.BlockBreakingMovementBehaviour;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A moving drill or saw does not cut into a claim other than the one its contraption was put together in. */
@Mixin(BlockBreakingMovementBehaviour.class)
public abstract class ClaimContraptionDrillMixin {
    @Inject(method = "visitNewPosition", at = @At("HEAD"), cancellable = true)
    private void siftec$claim(MovementContext context, BlockPos pos, CallbackInfo ci) {
        if (context.world == null || context.world.isClientSide() || context.contraption == null || context.contraption.anchor == null) return;
        if (Claims.crosses(context.world, context.contraption.anchor, pos)) ci.cancel();
    }
}
