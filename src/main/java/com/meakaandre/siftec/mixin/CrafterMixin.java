package com.meakaandre.siftec.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.meakaandre.siftec.owner.Ownership;
import com.meakaandre.siftec.owner.RecipeLocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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

    /**
     * Every Crafter shares one small recipe cache. It is filled with nobody asking, so it holds the real recipe
     * and not one company's "locked"; the asking Crafter's company is checked on what comes out.
     */
    @WrapMethod(method = "getPotentialResults")
    private static Optional<RecipeHolder<CraftingRecipe>> siftec$locked(ServerLevel level, CraftingInput input,
                                                                        Operation<Optional<RecipeHolder<CraftingRecipe>>> original) {
        BlockEntity machine = Ownership.ticking();
        ServerPlayer player = Ownership.asking(null);
        Ownership.ticking(null);
        Optional<RecipeHolder<CraftingRecipe>> found;
        try {
            found = original.call(level, input);
        } finally {
            Ownership.ticking(machine);
            Ownership.asking(player);
        }
        if (found.isPresent() && RecipeLocks.blocked(found.get().value())) return Optional.empty();
        return found;
    }
}
