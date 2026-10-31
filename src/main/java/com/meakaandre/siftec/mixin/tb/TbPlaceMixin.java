package com.meakaandre.siftec.mixin.tb;

import com.meakaandre.siftec.compat.TravelersBackpackCompat;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A Traveler's Backpack is never placed as a block (by hand, by its quick-unequip, by anything that places items),
 * so it cannot be left in the world for others to open or break. The one exception is vanilla's: a creative player
 * placing a backpack item held in their hand (one taken from a creative tab). The worn backpack is never placed.
 */
@Pseudo
@Mixin(targets = "com.tiviacz.travelersbackpack.item.TravelersBackpackItem", remap = false)
public abstract class TbPlaceMixin {
    @Inject(method = "place(Lnet/minecraft/world/item/context/BlockPlaceContext;)Lnet/minecraft/world/InteractionResult;", at = @At("HEAD"), cancellable = true)
    private void siftec$neverPlaced(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir) {
        if (!TravelersBackpackCompat.mayPlace(context.getPlayer(), context.getItemInHand(), context.getHand())) {
            cir.setReturnValue(InteractionResult.FAIL);
        }
    }
}
