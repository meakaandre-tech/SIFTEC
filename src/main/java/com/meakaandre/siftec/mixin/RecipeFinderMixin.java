package com.meakaandre.siftec.mixin;

import com.meakaandre.siftec.owner.RecipeLocks;
import com.zurrtum.create.foundation.recipe.RecipeFinder;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** Create's own recipe search (the Saw and others) leaves out what the asking machine's company has not unlocked. */
@Mixin(value = RecipeFinder.class, remap = false)
public abstract class RecipeFinderMixin {
    @Inject(method = "get", at = @At("RETURN"), cancellable = true)
    private static void siftec$locked(CallbackInfoReturnable<List<RecipeHolder<?>>> cir) {
        List<RecipeHolder<?>> all = cir.getReturnValue();
        List<RecipeHolder<?>> allowed = RecipeLocks.allowed(all);
        if (allowed != all) cir.setReturnValue(allowed);
    }
}
