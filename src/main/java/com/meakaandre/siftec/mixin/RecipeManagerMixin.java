package com.meakaandre.siftec.mixin;

import com.meakaandre.siftec.owner.RecipeLocks;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/** A machine that remembers its last recipe (a furnace, say) still cannot use one its company has not unlocked. */
@Mixin(RecipeManager.class)
public abstract class RecipeManagerMixin {
    @Inject(method = "getRecipeFor", at = @At("RETURN"), cancellable = true)
    private void siftec$locked(CallbackInfoReturnable<Optional<RecipeHolder<?>>> cir) {
        Optional<RecipeHolder<?>> found = cir.getReturnValue();
        if (found.isPresent() && RecipeLocks.blocked(found.get())) cir.setReturnValue(Optional.empty());
    }
}
