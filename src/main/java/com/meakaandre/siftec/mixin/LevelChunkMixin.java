package com.meakaandre.siftec.mixin;

import com.meakaandre.siftec.building.Buildings;
import com.meakaandre.siftec.compat.SiftGate;
import com.meakaandre.siftec.owner.Ownership;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Every block entity that appears during a player's click gets that player's company as its owner; block changes reach the buildings. */
@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {
    /** The Sift's portal never forms in the Overworld, the Nether or the End: the Ancient City frame stays dead. */
    @Inject(method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Lnet/minecraft/world/level/block/state/BlockState;", at = @At("HEAD"), cancellable = true)
    private void siftec$noSiftPortal(BlockPos pos, BlockState state, int flags,
                                     CallbackInfoReturnable<BlockState> cir) {
        LevelChunk self = (LevelChunk) (Object) this;
        if (SiftGate.isPortal(state) && !self.getLevel().isClientSide() && self.getLevel().dimension() != SiftGate.SIFT) {
            cir.setReturnValue(null);
        }
    }

    /** Buildings check themselves again when a block in them changes (the map has its own hook, in MapMod). */
    @Inject(method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Lnet/minecraft/world/level/block/state/BlockState;", at = @At("RETURN"))
    private void siftec$mapChanged(BlockPos pos, BlockState state, int flags,
                                   CallbackInfoReturnable<BlockState> cir) {
        if (cir.getReturnValue() != null && ((LevelChunk) (Object) this).getLevel() instanceof ServerLevel level) {
            Buildings.blockChanged(level, pos);
        }
    }

    @Inject(method = "setBlockEntity", at = @At("HEAD"))
    private void siftec$owner(BlockEntity blockEntity, CallbackInfo ci) {
        Ownership.stamp(blockEntity);
    }
}
