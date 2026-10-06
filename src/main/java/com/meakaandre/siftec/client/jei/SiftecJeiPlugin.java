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
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * JEI: every recipe page that the player's company cannot use yet gets a red line across its foot saying
 * what unlocks it. This covers crafting, every Create machine, and the Hard Drive alternates.
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
        public void draw(T recipe, IRecipeCategory<T> category, IRecipeSlotsView slots, GuiGraphicsExtractor graphics, double mouseX, double mouseY) {
            List<ItemStack> results = new ArrayList<>();
            for (IRecipeSlotView slot : slots.getSlotViews(RecipeIngredientRole.OUTPUT)) slot.getItemStacks().findFirst().ifPresent(results::add);
            Component why = ClientLocks.recipe(recipe, results);
            if (why == null) return;
            Font font = net.minecraft.client.Minecraft.getInstance().font;
            int y = category.getHeight() - 9;
            graphics.fill(0, y - 1, Math.min(category.getWidth(), font.width(why) + 3), y + 9, 0xD0000000);
            graphics.text(font, why, 1, y, 0xFFFF5555, false);
        }
    }
}
