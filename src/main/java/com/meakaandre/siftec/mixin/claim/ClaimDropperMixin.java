package com.meakaandre.siftec.mixin.claim;

import com.meakaandre.siftec.claim.Claims;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.DropperBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A dropper (its own dispenseFrom) does not push into a container, or drop items, across someone else's claim border. */
@Mixin(DropperBlock.class)
public abstract class ClaimDropperMixin {
    @Inject(method = "dispenseFrom", at = @At("HEAD"), cancellable = true)
    private void siftec$claim(ServerLevel level, BlockState state, BlockPos pos, CallbackInfo ci) {
        if (state.hasProperty(DispenserBlock.FACING) && Claims.crosses(level, pos, pos.relative(state.getValue(DispenserBlock.FACING)))) ci.cancel();
    }
}
