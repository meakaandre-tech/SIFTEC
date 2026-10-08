package com.meakaandre.siftec.mixin.claim;

import com.meakaandre.siftec.claim.Claims;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A piston cannot push or pull blocks into, out of or within someone else's claim. */
@Mixin(PistonBaseBlock.class)
public abstract class ClaimPistonMixin {
    @Inject(method = "moveBlocks", at = @At("HEAD"), cancellable = true)
    private void siftec$claimBorder(Level level, BlockPos pos, Direction direction, boolean extending, CallbackInfoReturnable<Boolean> cir) {
        if (level.isClientSide()) return;
        if (extending && Claims.crosses(level, pos, pos.relative(direction))) {
            cir.setReturnValue(false);
            return;
        }
        PistonStructureResolver resolver = new PistonStructureResolver(level, pos, direction, extending);
        if (!resolver.resolve()) return;
        Direction push = resolver.getPushDirection();
        for (BlockPos moved : resolver.getToPush()) {
            if (Claims.crosses(level, pos, moved) || Claims.crosses(level, pos, moved.relative(push))) {
                cir.setReturnValue(false);
                return;
            }
        }
        for (BlockPos broken : resolver.getToDestroy()) {
            if (Claims.crosses(level, pos, broken)) {
                cir.setReturnValue(false);
                return;
            }
        }
    }
}
