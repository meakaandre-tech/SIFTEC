package com.meakaandre.siftec.mixin.claim;

import com.meakaandre.siftec.claim.Claims;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Hoppers (and hopper minecarts) do not take from, or push into, someone else's claim across its border. */
@Mixin(HopperBlockEntity.class)
public abstract class ClaimHopperMixin {
    @Inject(method = "suckInItems", at = @At("HEAD"), cancellable = true)
    private static void siftec$noSiphon(Level level, Hopper hopper, CallbackInfoReturnable<Boolean> cir) {
        if (level.isClientSide()) return;
        BlockPos at = BlockPos.containing(hopper.getLevelX(), hopper.getLevelY(), hopper.getLevelZ());
        if (Claims.crosses(level, at, at.above())) cir.setReturnValue(false);
    }

    @Inject(method = "ejectItems", at = @At("HEAD"), cancellable = true)
    private static void siftec$noPush(Level level, BlockPos pos, HopperBlockEntity hopper, CallbackInfoReturnable<Boolean> cir) {
        BlockState state = level.getBlockState(pos);
        if (state.hasProperty(HopperBlock.FACING) && Claims.crosses(level, pos, pos.relative(state.getValue(HopperBlock.FACING)))) cir.setReturnValue(false);
    }
}
