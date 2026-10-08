package com.meakaandre.siftec.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.meakaandre.siftec.owner.Ownership;
import com.zurrtum.create.foundation.recipe.trie.RecipeTrie;
import com.zurrtum.create.foundation.recipe.trie.RecipeTrieFinder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;

import java.util.function.Predicate;

/**
 * The Basin's recipe index is built once and shared by every Basin, so it has to hold every recipe: nothing
 * is left out while it is being built. What a machine may not run is taken out when it looks things up.
 */
@Mixin(value = RecipeTrieFinder.class, remap = false)
public abstract class RecipeTrieFinderMixin {
    @WrapMethod(method = "get")
    private static RecipeTrie<Recipe<?>> siftec$whole(Object key, ServerLevel level, Predicate<RecipeHolder<? extends Recipe<?>>> conditions,
                                                     Operation<RecipeTrie<Recipe<?>>> original) {
        BlockEntity asking = Ownership.ticking();
        net.minecraft.server.level.ServerPlayer player = Ownership.asking(null);
        Ownership.ticking(null);
        try {
            return original.call(key, level, conditions);
        } finally {
            Ownership.ticking(asking);
            Ownership.asking(player);
        }
    }
}
