package com.meakaandre.siftec.mixin.claim;

import com.meakaandre.siftec.claim.Claims;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A dispenser outside a claim does nothing into it: it places no block, pours or picks up no fluid, and shears, plants
 * or fires nothing at the block it faces when that block is in someone else's claim. (A bucket it empties into an empty
 * resource node is already refused by NodeBucketMixin.)
 */
@Mixin(DispenserBlock.class)
public abstract class ClaimDispenserMixin {
    @Inject(method = "dispenseFrom", at = @At("HEAD"), cancellable = true)
    private void siftec$claim(ServerLevel level, BlockState state, BlockPos pos, CallbackInfo ci) {
        if (!state.hasProperty(DispenserBlock.FACING)) return;
        BlockPos target = pos.relative(state.getValue(DispenserBlock.FACING));
        if (Claims.crosses(level, pos, target)) ci.cancel();
    }
}
