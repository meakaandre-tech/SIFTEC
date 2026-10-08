package com.meakaandre.siftec.mixin.claim;

import com.meakaandre.siftec.claim.Claims;
import com.zurrtum.create.foundation.blockEntity.behaviour.inventory.CapManipulationBehaviourBase;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Funnels, crafters, basins and the like do not attach to an inventory across someone else's claim border. */
@Mixin(CapManipulationBehaviourBase.class)
public abstract class ClaimFunnelMixin {
    @Shadow
    protected Object targetCapability;

    @Inject(method = "findNewCapability", at = @At("HEAD"), cancellable = true)
    private void siftec$claim(CallbackInfo ci) {
        CapManipulationBehaviourBase<?, ?> self = (CapManipulationBehaviourBase<?, ?>) (Object) this;
        Level level = self.getLevel();
        if (level == null || level.isClientSide()) return;
        BlockPos target = self.getTarget().getOpposite().getPos();
        if (Claims.crosses(level, self.getPos(), target)) {
            targetCapability = null;
            ci.cancel();
        }
    }
}
