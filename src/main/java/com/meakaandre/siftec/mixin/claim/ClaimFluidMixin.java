package com.meakaandre.siftec.mixin.claim;

import com.meakaandre.siftec.claim.Claims;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Water and lava poured outside a claim do not flow into it. */
@Mixin(FlowingFluid.class)
public abstract class ClaimFluidMixin {
    @Inject(method = "spreadTo", at = @At("HEAD"), cancellable = true)
    private void siftec$claimBorder(LevelAccessor level, BlockPos pos, BlockState state, Direction direction, FluidState fluid, CallbackInfo ci) {
        if (level instanceof ServerLevel server && Claims.crosses(server, pos.relative(direction.getOpposite()), pos)) ci.cancel();
    }
}
