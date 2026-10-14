package com.meakaandre.siftec.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.meakaandre.siftec.owner.Ownership;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * While a machine ticks, recipe lookups know which machine is asking. Only on the server (the client asks nothing),
 * and restored even if the tick throws.
 */
@Mixin(targets = "net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity")
public abstract class BoundTickerMixin {
    @Shadow @Final
    private BlockEntity blockEntity;

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
        } finally {
            Ownership.ticking(before);
        }
    }
}
