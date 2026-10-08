package com.meakaandre.siftec.mixin.claim;

import com.meakaandre.siftec.claim.Claims;
import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.hub.Locks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Checks where a block would really go, not just the block that was clicked: clicking the outside face of a
 * block next to a claim would otherwise place one block into it. Deployers place through here too, so this is
 * also where their placements meet claims and milestone locks.
 */
@Mixin(BlockItem.class)
public abstract class ClaimBlockItemMixin {
    @Inject(method = "place(Lnet/minecraft/world/item/context/BlockPlaceContext;)Lnet/minecraft/world/InteractionResult;", at = @At("HEAD"), cancellable = true)
    private void siftec$placeCheck(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir) {
        if (!(context.getPlayer() instanceof ServerPlayer player)) return;
        Level level = context.getLevel();
        BlockPos target = context.getClickedPos();
        if (!Claims.allowed(player, level, target)) {
            Claims.deny(player, level, target);
            Claims.resync(player, level, target, context.getClickedFace());
            cir.setReturnValue(InteractionResult.FAIL);
        } else if (Companies.isFake(player) && !Locks.allowed(player, context.getItemInHand())) {
            cir.setReturnValue(InteractionResult.FAIL);
        }
    }
}
