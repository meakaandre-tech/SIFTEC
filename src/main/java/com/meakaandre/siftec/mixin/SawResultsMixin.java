package com.meakaandre.siftec.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.meakaandre.siftec.owner.Boosts;
import com.zurrtum.create.catnip.data.Pair;
import com.zurrtum.create.content.kinetics.saw.SawBlockEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;

import java.util.List;

/**
 * A Saw with a Somersloop doubles whatever it cuts: its own cutting recipes and stonecutting alike (stonecutting
 * results do not go through Create's output rolls). The whole result list is doubled once, here, and the rolls
 * inside are left alone, so nothing is doubled twice.
 */
@Mixin(value = SawBlockEntity.class, remap = false)
public abstract class SawResultsMixin {
    @WrapMethod(method = "getResults")
    private static List<ItemStack> siftec$doubleCuts(Level level, SingleRecipeInput input, ItemStack stack, Pair<Recipe<SingleRecipeInput>, ItemStack> pair,
                                                    Operation<List<ItemStack>> original) {
        boolean doubling = Boosts.doubling();
        List<ItemStack> results = Boosts.withoutDoubling(() -> original.call(level, input, stack, pair));
        return doubling ? Boosts.doubled(results, stack.getItem().getCraftingRemainder()) : results;
    }
}
