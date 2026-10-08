package com.meakaandre.siftec.mixin.claim;

import com.meakaandre.siftec.claim.Claims;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A HUB or Claim Marker gives its land up however it goes: broken by hand, blown up, replaced by a command,
 * taken by a drill or a Deployer. Every one of those ends in this call; unloading a chunk does not.
 */
@Mixin(LevelChunk.class)
public abstract class ClaimChunkMixin {
    @Inject(method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Lnet/minecraft/world/level/block/state/BlockState;", at = @At("RETURN"))
    private void siftec$releaseClaim(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> cir) {
        BlockState old = cir.getReturnValue();
        if (old == null || old.is(state.getBlock()) || !Claims.holdsClaim(old.getBlock())) return;
        if (!(((LevelChunk) (Object) this).getLevel() instanceof ServerLevel level)) return;
        BlockPos at = pos.immutable();
        level.getServer().execute(() -> {
            // put back in the same tick (a failed marker placement hands the item back first): nothing to do
            if (!level.isLoaded(at) || !Claims.holdsClaim(level.getBlockState(at).getBlock())) Claims.release(level, at);
        });
    }
}
