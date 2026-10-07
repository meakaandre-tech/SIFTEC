package com.meakaandre.siftec.client.jei;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.client.ClientLocks;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.category.extensions.IRecipeCategoryDecorator;
import mezz.jei.api.recipe.types.IRecipeType;
import mezz.jei.api.registration.IAdvancedRegistration;
import mezz.jei.api.gui.builder.ITooltipBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * JEI: hovering over a recipe that the player's company cannot use yet shows, in red, what unlocks it.
 * Nothing is drawn on the page itself. This covers crafting, every Create machine, and the Hard Drive alternates.
 */
@JeiPlugin
public class SiftecJeiPlugin implements IModPlugin {
    private static final Identifier ID = Siftec.id("jei");

    @Override
    public Identifier getPluginUid() {
        return ID;
    }

    @Override
    public void registerAdvanced(IAdvancedRegistration registration) {
        registration.getJeiHelpers().getAllRecipeTypes().forEach(type -> decorate(registration, type));
    }

    private static <T> void decorate(IAdvancedRegistration registration, IRecipeType<T> type) {
        registration.addRecipeCategoryDecorator(type, new LockLine<>());
    }

    private static final class LockLine<T> implements IRecipeCategoryDecorator<T> {
        @Override
        public void decorateTooltips(ITooltipBuilder tooltip, T recipe, IRecipeCategory<T> category, IRecipeSlotsView slots, double mouseX, double mouseY) {
            List<ItemStack> results = new ArrayList<>();
            for (IRecipeSlotView slot : slots.getSlotViews(RecipeIngredientRole.OUTPUT)) slot.getItemStacks().findFirst().ifPresent(results::add);
            Component why = ClientLocks.recipe(recipe, results);
            if (why != null) tooltip.add(why.copy().withStyle(ChatFormatting.RED));
        }
    }
}
