package com.meakaandre.siftec.mixin.belt;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.meakaandre.siftec.belt.BeltAttachments;
import com.zurrtum.create.content.kinetics.belt.BeltBlock;
import com.zurrtum.create.content.kinetics.belt.BeltBlockEntity;
import com.zurrtum.create.content.kinetics.belt.BeltSlope;
import com.zurrtum.create.content.kinetics.belt.transport.TransportedItemStack;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Items onto a belt from its side: a vertical belt takes them from a horizontal side (a belt running into its foot), and
 * whatever puts them on (a belt, a funnel, a chute, outside any belt's tick) finds a built-in brass tunnel as Create
 * finds a stacked one.
 */
@Mixin(value = BeltBlockEntity.class, remap = false)
public abstract class BeltVerticalCarryMixin {
    @Inject(method = "canInsertFrom", at = @At("HEAD"), cancellable = true)
    private void siftec$verticalTakes(Direction side, CallbackInfoReturnable<Boolean> cir) {
        BeltBlockEntity self = (BeltBlockEntity) (Object) this;
        if (self.getBlockState().getValue(BeltBlock.SLOPE) == BeltSlope.VERTICAL && side.getAxis().isHorizontal()) cir.setReturnValue(self.getSpeed() != 0);
    }

    @WrapMethod(method = "tryInsertingFromSide")
    private ItemStack siftec$insertInView(TransportedItemStack stack, Direction side, boolean simulate, Operation<ItemStack> original) {
        return BeltAttachments.call(() -> original.call(stack, side, simulate));
    }
}
