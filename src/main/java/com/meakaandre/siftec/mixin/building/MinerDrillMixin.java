package com.meakaandre.siftec.mixin.building;

import com.meakaandre.siftec.building.BuildingLogic;
import com.meakaandre.siftec.building.ControllerBlockEntity;
import com.zurrtum.create.content.kinetics.base.BlockBreakingKineticBlockEntity;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A miner's Drill breaks its node at its Mk's speed ({@link BuildingLogic#breakSpeed}), whatever it turns at. */
@Mixin(value = BlockBreakingKineticBlockEntity.class, remap = false)
public abstract class MinerDrillMixin {
    @Shadow
    protected BlockPos breakingPos;

    @Inject(method = "getBreakSpeed", at = @At("HEAD"), cancellable = true)
    private void siftec$mkSpeed(CallbackInfoReturnable<Float> cir) {
        BlockBreakingKineticBlockEntity self = (BlockBreakingKineticBlockEntity) (Object) this;
        if (self.getLevel() == null || self.getLevel().isClientSide() || breakingPos == null) return;
        ControllerBlockEntity miner = BuildingLogic.minerOf(self.getLevel(), self.getBlockPos(), breakingPos);
        if (miner == null) return;
        float hardness = self.getLevel().getBlockState(breakingPos).getDestroySpeed(self.getLevel(), breakingPos);
        cir.setReturnValue(BuildingLogic.breakSpeed(miner, hardness));
    }
}
