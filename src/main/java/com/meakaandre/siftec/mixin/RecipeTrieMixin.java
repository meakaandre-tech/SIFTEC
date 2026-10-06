package com.meakaandre.siftec.mixin;

import com.meakaandre.siftec.owner.RecipeLocks;
import com.zurrtum.create.foundation.recipe.trie.RecipeTrie;
import net.minecraft.world.item.crafting.Recipe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** A Basin (Mixer, Press over a Basin) is only offered the recipes its company has unlocked. */
@Mixin(value = RecipeTrie.class, remap = false)
public abstract class RecipeTrieMixin {
    @Inject(method = "lookup", at = @At("RETURN"), cancellable = true)
    private void siftec$locked(CallbackInfoReturnable<List<Recipe<?>>> cir) {
        List<Recipe<?>> all = cir.getReturnValue();
        List<Recipe<?>> allowed = RecipeLocks.allowed(all);
        if (allowed != all) cir.setReturnValue(allowed);
    }
}
