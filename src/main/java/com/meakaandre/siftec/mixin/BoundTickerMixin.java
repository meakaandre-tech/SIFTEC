package com.meakaandre.siftec.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.meakaandre.siftec.owner.Ownership;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * While a machine ticks, recipe lookups know which machine is asking; afterwards a machine with Power Shards
 * ticks again. Only on the server (the client asks nothing of either), restored even if the tick throws, and the
 * shard lookup only for Create's machines, the only ones that take shards.
 */
@Mixin(targets = "net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity")
public abstract class BoundTickerMixin {
    @Shadow @Final
    private BlockEntity blockEntity;

    @Shadow @Final
    private BlockEntityTicker<?> ticker;

    @WrapMethod(method = "tick")
    private void siftec$asMachine(Operation<Void> original) {
        Level level = blockEntity.getLevel();
        if (level == null || level.isClientSide()) {
            original.call();
            return;
        }
        BlockEntity before = Ownership.ticking();
        Ownership.ticking(blockEntity);
        try {
            original.call();
            if (blockEntity instanceof com.zurrtum.create.foundation.blockEntity.SmartBlockEntity) {
                com.meakaandre.siftec.owner.Boosts.extraTicks(blockEntity, ticker);
            }
        } finally {
            Ownership.ticking(before);
        }
    }
}
