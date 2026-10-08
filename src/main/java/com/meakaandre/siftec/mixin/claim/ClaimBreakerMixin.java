package com.meakaandre.siftec.mixin.claim;

import com.meakaandre.siftec.claim.Claims;
import com.zurrtum.create.content.kinetics.base.BlockBreakingKineticBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** A drill or saw only cuts into a claim its company holds (or one it stands in). */
@Mixin(BlockBreakingKineticBlockEntity.class)
public abstract class ClaimBreakerMixin {
    @Shadow
    protected BlockPos breakingPos;

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lcom/zurrtum/create/content/kinetics/base/BlockBreakingKineticBlockEntity;canBreak(Lnet/minecraft/world/level/block/state/BlockState;F)Z"))
    private boolean siftec$claim(BlockBreakingKineticBlockEntity self, BlockState state, float hardness) {
        return self.canBreak(state, hardness) && (breakingPos == null || Claims.machineMayBreak(self, breakingPos));
    }
}
