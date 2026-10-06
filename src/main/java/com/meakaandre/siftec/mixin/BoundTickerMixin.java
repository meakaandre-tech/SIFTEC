package com.meakaandre.siftec.mixin;

import com.meakaandre.siftec.owner.Ownership;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While a machine ticks, recipe lookups know which machine is asking. */
@Mixin(targets = "net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity")
public abstract class BoundTickerMixin {
    @Shadow @Final
    private BlockEntity blockEntity;

    @Inject(method = "tick", at = @At("HEAD"))
    private void siftec$enter(CallbackInfo ci) {
        Ownership.ticking(blockEntity);
    }

    @Shadow @Final
    private net.minecraft.world.level.block.entity.BlockEntityTicker<?> ticker;

    @Inject(method = "tick", at = @At("RETURN"))
    private void siftec$leave(CallbackInfo ci) {
        // Power Shards: a boosted machine ticks again, still as the machine that is asking
        com.meakaandre.siftec.owner.Boosts.extraTicks(blockEntity, ticker);
        Ownership.ticking(null);
    }
}
