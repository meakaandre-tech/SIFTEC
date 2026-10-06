package com.meakaandre.siftec.mixin;

import com.meakaandre.siftec.owner.Boosts;
import com.zurrtum.create.content.processing.recipe.ProcessingOutput;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A Create machine with a Somersloop in it gets twice what each recipe gives. */
@Mixin(value = ProcessingOutput.class, remap = false)
public abstract class ProcessingOutputMixin {
    @Inject(method = "rollOutput(Lnet/minecraft/util/RandomSource;)Lnet/minecraft/world/item/ItemStack;", at = @At("RETURN"), cancellable = true)
    private void siftec$amplify(RandomSource random, CallbackInfoReturnable<ItemStack> cir) {
        ItemStack made = cir.getReturnValue();
        if (made == null || made.isEmpty() || !Boosts.doubling()) return;
        ItemStack doubled = made.copy();
        doubled.setCount(Math.min(doubled.getMaxStackSize(), made.getCount() * 2));
        cir.setReturnValue(doubled);
    }
}
