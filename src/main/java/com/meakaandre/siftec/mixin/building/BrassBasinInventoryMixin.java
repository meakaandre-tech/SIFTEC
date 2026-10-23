package com.meakaandre.siftec.mixin.building;

import com.meakaandre.siftec.building.BrassBasinBlock;
import com.zurrtum.create.content.processing.basin.BasinBlockEntity;
import com.zurrtum.create.content.processing.basin.BasinInventory;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A Basin keeps each kind of item in one input slot, so it holds one stack of it. In a Brass Basin an item may take up to
 * {@link BrassBasinBlock#STACKS} of the nine input slots, a new one only once the ones it has are full.
 */
@Mixin(value = BasinInventory.class, remap = false)
public abstract class BrassBasinInventoryMixin {
    /** A Basin's input slots. */
    @Unique
    private static final int INPUT_SLOTS = 9;

    @Shadow
    @Final
    private BasinBlockEntity blockEntity;

    @Shadow
    @Final
    private NonNullList<ItemStack> stacks;

    @Shadow
    private boolean check;

    @Inject(method = "canPlaceItem", at = @At("HEAD"), cancellable = true)
    private void siftec$brassStacks(int slot, ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (!check || slot >= INPUT_SLOTS || blockEntity == null || !(blockEntity.getBlockState().getBlock() instanceof BrassBasinBlock)) return;
        ItemStack here = stacks.get(slot);
        if (!here.isEmpty()) {
            cir.setReturnValue(ItemStack.isSameItemSameComponents(here, stack));
            return;
        }
        int held = 0;
        for (int i = 0; i < INPUT_SLOTS; i++) {
            ItemStack other = stacks.get(i);
            if (i == slot || other.isEmpty() || !ItemStack.isSameItemSameComponents(other, stack)) continue;
            // a new slot only once the item's other slots are full
            if (other.getCount() < other.getMaxStackSize()) {
                cir.setReturnValue(false);
                return;
            }
            held++;
        }
        cir.setReturnValue(held < BrassBasinBlock.STACKS);
    }
}
