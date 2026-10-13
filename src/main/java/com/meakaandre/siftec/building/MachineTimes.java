package com.meakaandre.siftec.building;

import com.google.gson.JsonObject;
import com.meakaandre.siftec.hub.Milestones;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Satisfactory's time for each recipe, which the buildings keep to (siftec_content.json, written by
 * tools/gen_assets.py): "machine_ticks" for the recipes a building's Basin machines run, "smelter_ticks" for the
 * Smelter's time per item. What generators burn is {@link BuildingPower}'s.
 */
public final class MachineTimes {
    private static volatile Map<Recipe<?>, Integer> table;
    private static Map<Item, Integer> smelter;

    private MachineTimes() {
    }

    /** Recipes are reloaded with the datapacks, so the table is rebuilt the next time it is needed. */
    public static void forget() {
        table = null;
    }

    private static JsonObject section(String name) {
        JsonObject raw = Milestones.raw();
        return raw != null && raw.has(name) ? raw.getAsJsonObject(name) : new JsonObject();
    }

    /** Ticks one run of a recipe takes in a building, or 0 if it is not one Satisfactory times. */
    public static int ticks(MinecraftServer server, Recipe<?> recipe) {
        Map<Recipe<?>, Integer> map = table;
        if (map == null) {
            map = new IdentityHashMap<>();
            JsonObject times = section("machine_ticks");
            for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) {
                String key = holder.id().identifier().toString();
                if (times.has(key)) map.put(holder.value(), times.get(key).getAsInt());
            }
            table = map;
        }
        return map.getOrDefault(recipe, 0);
    }

    /** Ticks the Smelter takes per item of this ore, or 0 if it does not smelt it. */
    public static synchronized int smelterTicks(ItemStack stack) {
        if (smelter == null) {
            smelter = new HashMap<>();
            JsonObject times = section("smelter_ticks");
            for (String id : times.keySet()) {
                BuiltInRegistries.ITEM.getOptional(net.minecraft.resources.Identifier.parse(id)).ifPresent(item -> smelter.put(item, times.get(id).getAsInt()));
            }
        }
        return stack.isEmpty() ? 0 : smelter.getOrDefault(stack.getItem(), 0);
    }
}
