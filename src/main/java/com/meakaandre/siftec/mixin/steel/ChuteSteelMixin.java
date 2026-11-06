package com.meakaandre.siftec.mixin.steel;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.meakaandre.siftec.registry.ModBlocks;
import com.zurrtum.create.content.logistics.chute.ChuteBlock;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** A chute is encased with SIFTEC's Steel Casing, as Create encases it with industrial iron (which SIFTEC replaces).
 * Create Fly's jar calls ItemStack.is(Object), so that is the call wrapped. */
@Mixin(ChuteBlock.class)
public abstract class ChuteSteelMixin {
    @WrapOperation(method = "useItemOn", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;is(Ljava/lang/Object;)Z"))
    private boolean siftec$steelCasing(ItemStack stack, Object item, Operation<Boolean> original) {
        return original.call(stack, item) || stack.is(ModBlocks.STEEL_CASING.get().asItem());
    }
}
