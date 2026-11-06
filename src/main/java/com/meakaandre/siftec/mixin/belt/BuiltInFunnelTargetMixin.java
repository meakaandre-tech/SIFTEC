package com.meakaandre.siftec.mixin.belt;

import com.meakaandre.siftec.belt.BeltAttachments;
import com.zurrtum.create.api.behaviour.BlockEntityBehaviour;
import com.zurrtum.create.catnip.math.BlockFace;
import com.zurrtum.create.content.logistics.funnel.FunnelBlockEntity;
import com.zurrtum.create.foundation.blockEntity.SmartBlockEntity;
import com.zurrtum.create.foundation.blockEntity.behaviour.inventory.CapManipulationBehaviourBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A built-in funnel feeds the block beside its belt at the belt's height: its inventory target is one block lower than
 * a stacked funnel's (which stands a block above the belt).
 */
@Mixin(value = CapManipulationBehaviourBase.class, remap = false)
public abstract class BuiltInFunnelTargetMixin {
    @Inject(method = "getTarget", at = @At("RETURN"), cancellable = true)
    private void siftec$atBeltHeight(CallbackInfoReturnable<BlockFace> cir) {
        SmartBlockEntity be = ((BlockEntityBehaviour<?>) (Object) this).blockEntity;
        if (!(be instanceof FunnelBlockEntity) || BeltAttachments.hostOf(be) == null) return;
        BlockFace face = cir.getReturnValue();
        cir.setReturnValue(new BlockFace(face.getPos().below(), face.getFace()));
    }
}
