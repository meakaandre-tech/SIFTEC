package com.meakaandre.siftec.mixin.claim;

import com.meakaandre.siftec.claim.Claims;
import com.zurrtum.create.content.contraptions.Contraption;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A piston, bearing or gantry cannot pick up blocks from a claim other than the one it is assembled in. */
@Mixin(Contraption.class)
public abstract class ClaimAssemblyMixin {
    @Inject(method = "searchMovedStructure", at = @At("RETURN"), cancellable = true)
    private void siftec$claim(Level level, BlockPos pos, Direction forcedDirection, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() || level.isClientSide()) return;
        Contraption self = (Contraption) (Object) this;
        BlockPos anchor = self.anchor;
        for (BlockPos local : self.getBlocks().keySet()) {
            if (Claims.crosses(level, anchor, local.offset(anchor))) {
                cir.setReturnValue(false);
                return;
            }
        }
    }
}
