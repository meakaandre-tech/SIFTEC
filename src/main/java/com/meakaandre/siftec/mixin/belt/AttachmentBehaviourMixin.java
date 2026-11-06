package com.meakaandre.siftec.mixin.belt;

import com.meakaandre.siftec.belt.BeltAttachments;
import com.zurrtum.create.api.behaviour.BlockEntityBehaviour;
import com.zurrtum.create.content.kinetics.belt.BeltBlockEntity;
import com.zurrtum.create.foundation.blockEntity.SmartBlockEntity;
import com.zurrtum.create.foundation.blockEntity.behaviour.BehaviourType;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A belt block with a built-in funnel or tunnel also answers for the attachment's behaviours it has none of itself
 * (filter, scroll values, the brass tunnel's mode), so Create's value boxes work by clicking the belt block and their
 * packets reach the attachment.
 */
@Mixin(value = BlockEntityBehaviour.class, remap = false)
public abstract class AttachmentBehaviourMixin {
    @Inject(method = "get(Lnet/minecraft/world/level/block/entity/BlockEntity;Lcom/zurrtum/create/foundation/blockEntity/behaviour/BehaviourType;)Lcom/zurrtum/create/api/behaviour/BlockEntityBehaviour;",
        at = @At("RETURN"), cancellable = true)
    private static void siftec$attachmentBehaviour(BlockEntity be, BehaviourType<?> type, CallbackInfoReturnable<BlockEntityBehaviour<?>> cir) {
        if (cir.getReturnValue() != null || !(be instanceof BeltBlockEntity belt)) return;
        SmartBlockEntity a = BeltAttachments.of(belt);
        if (a != null) cir.setReturnValue(a.getBehaviour(type));
    }
}
