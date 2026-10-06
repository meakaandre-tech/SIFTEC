package com.meakaandre.siftec.owner;

import com.google.gson.JsonObject;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.hub.Milestones;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Recipes are unlocked per company. A machine only runs a locked recipe once the company it belongs to has
 * finished the milestone that unlocks the result. Which recipe needs which milestone is listed in
 * siftec_content.json ("recipe_locks"); crafting by hand is handled by {@link com.meakaandre.siftec.hub.Locks}.
 */
public final class RecipeLocks {
    private static final String SEQUENCE = "sequenced_assembly_";
    private static volatile Map<Recipe<?>, String> table;

    private RecipeLocks() {
    }

    /** Recipes are reloaded with the datapacks, so the table is rebuilt the next time it is needed. */
    public static void forget() {
        table = null;
    }

    private static Map<Recipe<?>, String> table(MinecraftServer server) {
        Map<Recipe<?>, String> map = table;
        if (map != null) return map;
        map = new IdentityHashMap<>();
        JsonObject locks = Milestones.raw().getAsJsonObject("recipe_locks");
        for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) {
            Identifier id = holder.id().identifier();
            String key = id.toString();
            // Create turns each step of a sequenced assembly into a recipe named after the final result
            if (id.getNamespace().equals("create") && id.getPath().startsWith(SEQUENCE)) {
                String rest = id.getPath().substring(SEQUENCE.length());
                int from = rest.indexOf('_'), to = rest.lastIndexOf('_');
                if (from >= 0 && to > from) key = "seq:" + rest.substring(from + 1, to);
            }
            if (locks.has(key)) map.put(holder.value(), locks.get(key).getAsString());
        }
        // any other recipe, from any mod, that makes an item a milestone unlocks is locked to that milestone too
        net.minecraft.util.context.ContextMap context = net.minecraft.world.item.crafting.display.SlotDisplayContext.fromLevel(server.overworld());
        for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) {
            if (map.containsKey(holder.value())) continue;
            try {
                search:
                for (net.minecraft.world.item.crafting.display.RecipeDisplay display : holder.value().display()) {
                    for (net.minecraft.world.item.ItemStack made : display.result().resolveForStacks(context)) {
                        com.meakaandre.siftec.hub.Milestone lock = made.isEmpty() ? null : com.meakaandre.siftec.hub.Locks.lockOf(made.getItem());
                        if (lock == null) continue;
                        map.put(holder.value(), lock.id());
                        break search;
                    }
                }
            } catch (RuntimeException ignored) {
                // a recipe that cannot say what it makes this way is left unlocked
            }
        }
        table = map;
        return map;
    }

    /** How many loaded recipes are locked to a milestone. */
    public static int count(MinecraftServer server) {
        return table(server).size();
    }

    /** The milestone a recipe needs, or null. */
    public static String lockOf(MinecraftServer server, Recipe<?> recipe) {
        return table(server).get(recipe);
    }

    /** True if the machine may not run this recipe. */
    public static boolean blocked(BlockEntity machine, Recipe<?> recipe) {
        if (!(machine.getLevel() instanceof ServerLevel level)) return false;
        String lock = table(level.getServer()).get(recipe);
        if (lock == null) return false;
        Company company = Ownership.of(machine);
        return company == null || !company.has(lock);
    }

    /** True if the machine ticking right now may not run this recipe. Nothing is blocked outside a machine's tick. */
    public static boolean blocked(Recipe<?> recipe) {
        BlockEntity machine = Ownership.ticking();
        return machine != null && blocked(machine, recipe);
    }

    public static boolean blocked(RecipeHolder<?> holder) {
        return blocked(holder.value());
    }

    /** The list without the recipes the ticking machine may not run; the same list when nothing is taken out. */
    public static <T> List<T> allowed(List<T> list) {
        BlockEntity machine = Ownership.ticking();
        if (machine == null || list.isEmpty()) return list;
        List<T> kept = null;
        for (int i = 0; i < list.size(); i++) {
            T entry = list.get(i);
            Recipe<?> recipe = entry instanceof RecipeHolder<?> holder ? holder.value() : entry instanceof Recipe<?> r ? r : null;
            boolean blocked = recipe != null && blocked(machine, recipe);
            if (blocked && kept == null) kept = new ArrayList<>(list.subList(0, i));
            else if (!blocked && kept != null) kept.add(entry);
        }
        return kept == null ? list : kept;
    }
}
