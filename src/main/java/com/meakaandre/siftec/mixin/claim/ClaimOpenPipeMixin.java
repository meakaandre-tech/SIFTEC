package com.meakaandre.siftec.mixin.claim;

import com.meakaandre.siftec.claim.Claims;
import com.zurrtum.create.content.fluids.OpenEndedPipe;
import com.zurrtum.create.infrastructure.fluids.FluidStack;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * An open-ended Create fluid pipe neither pours fluid into nor drains it out of the block in front of it when that block
 * is in someone else's claim (seen from the pipe's own block), and pours nothing into an empty resource node.
 */
@Mixin(OpenEndedPipe.class)
public abstract class ClaimOpenPipeMixin {
    @Shadow
    private Level world;

    @Shadow
    @Final
    private BlockPos pos;

    @Shadow
    @Final
    private BlockPos outputPos;

    @Unique
    private boolean siftec$blocked() {
        return world != null && !world.isClientSide() && Claims.crosses(world, pos, outputPos);
    }

    @Inject(method = "provideFluidToSpace", at = @At("HEAD"), cancellable = true)
    private void siftec$noPour(FluidStack fluid, boolean simulate, CallbackInfoReturnable<Boolean> cir) {
        if (siftec$blocked() || (world != null && com.meakaandre.siftec.node.NodeSites.isSite(world, outputPos))) cir.setReturnValue(false);
    }

    @Inject(method = "removeFluidFromSpace", at = @At("HEAD"), cancellable = true)
    private void siftec$noDrain(boolean simulate, CallbackInfoReturnable<FluidStack> cir) {
        if (siftec$blocked()) cir.setReturnValue(FluidStack.EMPTY);
    }
}
