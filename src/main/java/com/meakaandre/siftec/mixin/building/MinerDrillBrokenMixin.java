package com.meakaandre.siftec.mixin.building;

import com.meakaandre.siftec.building.BuildingLogic;
import com.meakaandre.siftec.building.ControllerBlockEntity;
import com.zurrtum.create.content.kinetics.base.DirectionalKineticBlock;
import com.zurrtum.create.content.kinetics.drill.DrillBlockEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * When a miner's Drill has broken its node, what the node drops (mined with the miner's Fortune) goes into the miner's
 * output instead of onto the ground, and the node goes depleted to regrow ({@link BuildingLogic#mined}).
 */
@Mixin(value = DrillBlockEntity.class, remap = false)
public abstract class MinerDrillBrokenMixin {
    @Inject(method = "onBlockBroken", at = @At("HEAD"), cancellable = true)
    private void siftec$toMiner(BlockState state, CallbackInfo ci) {
        DrillBlockEntity self = (DrillBlockEntity) (Object) this;
        if (!(self.getLevel() instanceof ServerLevel level)) return;
        // the block it broke is the one in front of it, which for a miner's Drill is its node
        var front = self.getBlockPos().relative(self.getBlockState().getValue(DirectionalKineticBlock.FACING));
        ControllerBlockEntity miner = BuildingLogic.minerOf(level, self.getBlockPos(), front);
        if (miner == null) return;
        BuildingLogic.mined(level, miner, state);
        ci.cancel();
    }
}
