package com.meakaandre.siftec.mixin.claim;

import com.meakaandre.siftec.claim.Claims;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Fire outside a claim neither spreads into it nor burns its blocks away. */
@Mixin(FireBlock.class)
public abstract class ClaimFireMixin {
    @Unique
    private static final ThreadLocal<BlockPos> SIFTEC_FIRE = new ThreadLocal<>();

    @Inject(method = "tick", at = @At("HEAD"))
    private void siftec$fireStart(BlockState state, ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci) {
        SIFTEC_FIRE.set(pos);
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void siftec$fireEnd(BlockState state, ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci) {
        SIFTEC_FIRE.remove();
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private boolean siftec$spread(ServerLevel level, BlockPos target, BlockState state, int flags) {
        BlockPos fire = SIFTEC_FIRE.get();
        if (fire != null && Claims.crosses(level, fire, target)) return false;
        return level.setBlock(target, state, flags);
    }

    @Inject(method = "checkBurnOut", at = @At("HEAD"), cancellable = true)
    private void siftec$burn(Level level, BlockPos pos, int chance, RandomSource random, int age, CallbackInfo ci) {
        BlockPos fire = SIFTEC_FIRE.get();
        if (fire != null && Claims.crosses(level, fire, pos)) ci.cancel();
    }
}
