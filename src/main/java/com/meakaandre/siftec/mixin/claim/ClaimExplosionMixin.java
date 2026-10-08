package com.meakaandre.siftec.mixin.claim;

import com.meakaandre.siftec.claim.Claims;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/** Claimed land is not damaged by explosions, whoever set them off. */
@Mixin(ServerExplosion.class)
public abstract class ClaimExplosionMixin {
    @Shadow
    @Final
    private ServerLevel level;

    @Inject(method = "calculateExplodedPositions", at = @At("RETURN"), cancellable = true)
    private void siftec$spareClaims(CallbackInfoReturnable<List<BlockPos>> cir) {
        List<BlockPos> positions = cir.getReturnValue();
        if (positions == null || positions.isEmpty()) return;
        List<BlockPos> kept = new ArrayList<>(positions.size());
        for (BlockPos pos : positions) if (Claims.at(level, pos) == null) kept.add(pos);
        if (kept.size() != positions.size()) cir.setReturnValue(kept);
    }
}
