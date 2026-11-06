package com.meakaandre.siftec.mixin.belt;

import com.meakaandre.siftec.belt.BeltAttachments;
import com.zurrtum.create.content.logistics.funnel.FunnelItem;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A funnel used on a belt block clips into it. Create has its funnel item always place itself and skip the clicked
 * block's own use (so the belt's use mixin never sees a funnel), so the funnel is caught here, in the item's use.
 */
@Mixin(BlockItem.class)
public abstract class FunnelOnBeltMixin {
    @Inject(method = "useOn", at = @At("HEAD"), cancellable = true)
    private void siftec$clipFunnel(UseOnContext context, CallbackInfoReturnable<InteractionResult> cir) {
        if (!((Object) this instanceof FunnelItem) || context.getPlayer() == null) return;
        BlockHitResult hit = new BlockHitResult(context.getClickLocation(), context.getClickedFace(), context.getClickedPos(), context.isInside());
        InteractionResult placed = BeltAttachments.place(context.getItemInHand(), context.getLevel(), context.getClickedPos(), context.getPlayer(), context.getHand(), hit);
        if (placed != null) cir.setReturnValue(placed);
    }
}
