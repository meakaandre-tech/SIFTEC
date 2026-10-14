package com.meakaandre.siftec.mixin;

import com.meakaandre.siftec.owner.Ownership;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Every block entity that appears during a player's click gets that player's company as its owner. */
@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {
    /** The Sift's portal never forms in the Overworld, the Nether or the End: the Ancient City frame stays dead. */
    @Inject(method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Lnet/minecraft/world/level/block/state/BlockState;", at = @At("HEAD"), cancellable = true)
    private void siftec$noSiftPortal(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState state, int flags,
                                     org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<net.minecraft.world.level.block.state.BlockState> cir) {
        LevelChunk self = (LevelChunk) (Object) this;
        if (com.meakaandre.siftec.compat.SiftGate.isPortal(state) && !self.getLevel().isClientSide() && self.getLevel().dimension() != com.meakaandre.siftec.compat.SiftGate.SIFT) {
            cir.setReturnValue(null);
        }
    }

    @Inject(method = "setBlockEntity", at = @At("HEAD"))
    private void siftec$owner(BlockEntity blockEntity, CallbackInfo ci) {
        Ownership.stamp(blockEntity);
    }
}
