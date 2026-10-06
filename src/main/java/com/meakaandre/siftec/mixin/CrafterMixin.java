package com.meakaandre.siftec.mixin;

import com.meakaandre.siftec.owner.Ownership;
import com.meakaandre.siftec.owner.RecipeLocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/** The vanilla Crafter obeys the same rule as every other machine: it only makes what its company has unlocked. */
@Mixin(CrafterBlock.class)
public abstract class CrafterMixin {
    @Inject(method = "dispenseFrom", at = @At("HEAD"))
    private void siftec$enter(BlockState state, ServerLevel level, BlockPos pos, CallbackInfo ci) {
        Ownership.ticking(level.getBlockEntity(pos));
    }

    @Inject(method = "dispenseFrom", at = @At("RETURN"))
    private void siftec$leave(BlockState state, ServerLevel level, BlockPos pos, CallbackInfo ci) {
        Ownership.ticking(null);
    }

    @Inject(method = "getPotentialResults", at = @At("RETURN"), cancellable = true)
    private static void siftec$locked(CallbackInfoReturnable<Optional<RecipeHolder<CraftingRecipe>>> cir) {
        Optional<RecipeHolder<CraftingRecipe>> found = cir.getReturnValue();
        if (found.isPresent() && RecipeLocks.blocked(found.get())) cir.setReturnValue(Optional.empty());
    }
}
