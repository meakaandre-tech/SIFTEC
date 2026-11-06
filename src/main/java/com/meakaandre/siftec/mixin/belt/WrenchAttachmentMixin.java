package com.meakaandre.siftec.mixin.belt;

import com.meakaandre.siftec.belt.BeltAttachments;
import com.zurrtum.create.content.equipment.wrench.WrenchItem;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.UseOnContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The wrench on a belt block with a built-in funnel or tunnel: sneak to take it off, else wrench it as Create's own block. */
@Mixin(value = WrenchItem.class, remap = false)
public abstract class WrenchAttachmentMixin {
    @Inject(method = "useOn", at = @At("HEAD"), cancellable = true)
    private void siftec$attachment(UseOnContext context, CallbackInfoReturnable<InteractionResult> cir) {
        InteractionResult result = BeltAttachments.wrench(context);
        if (result != null) cir.setReturnValue(result);
    }
}
