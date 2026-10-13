package com.meakaandre.siftec.mixin.claim;

import com.meakaandre.siftec.claim.Claims;
import com.zurrtum.create.content.kinetics.base.BlockBreakingKineticBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * A drill or saw only cuts into a claim its company holds (or one it stands in), never into a building, and never from
 * inside one, except a miner's Drill into its own node.
 */
@Mixin(BlockBreakingKineticBlockEntity.class)
public abstract class ClaimBreakerMixin {
    @Shadow
    protected BlockPos breakingPos;

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lcom/zurrtum/create/content/kinetics/base/BlockBreakingKineticBlockEntity;canBreak(Lnet/minecraft/world/level/block/state/BlockState;F)Z"))
    private boolean siftec$claim(BlockBreakingKineticBlockEntity self, BlockState state, float hardness) {
        // a drill inside a building breaks nothing but its miner's own node, once it has regrown and the output has room
        if (com.meakaandre.siftec.building.Buildings.protectedAt(self.getLevel(), self.getBlockPos())) {
            var miner = com.meakaandre.siftec.building.BuildingLogic.minerOf(self.getLevel(), self.getBlockPos(), breakingPos);
            return miner != null && com.meakaandre.siftec.building.BuildingLogic.mayBreak(self.getLevel(), miner, state);
        }
        return self.canBreak(state, hardness) && (breakingPos == null || (Claims.machineMayBreak(self, breakingPos) && !com.meakaandre.siftec.building.Buildings.protectedAt(self.getLevel(), breakingPos)));
    }
}
