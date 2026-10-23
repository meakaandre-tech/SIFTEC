package com.meakaandre.siftec.mixin.claim;

import com.meakaandre.siftec.building.BuildingProtection;
import com.meakaandre.siftec.claim.Claims;
import com.zurrtum.create.content.contraptions.actors.psi.PortableStorageInterfaceMovement;
import com.zurrtum.create.content.contraptions.behaviour.MovementContext;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A moving Portable Storage Interface connects to a stationary one only inside the claim its contraption was put
 * together in (as moving drills cut only there), and never to one that is part of a building.
 */
@Mixin(value = PortableStorageInterfaceMovement.class, remap = false)
public abstract class ClaimPsiMixin {
    @Inject(method = "findInterface", at = @At("HEAD"), cancellable = true)
    private void siftec$claim(MovementContext context, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (context.world == null || context.world.isClientSide()) return;
        BlockPos from = context.contraption == null ? null : context.contraption.anchor;
        if (BuildingProtection.protectedAt(context.world, pos) || from != null && Claims.crosses(context.world, from, pos)) cir.setReturnValue(false);
    }
}
