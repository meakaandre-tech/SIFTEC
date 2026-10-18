package com.meakaandre.siftec.sim;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.meakaandre.siftec.Siftec;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import org.jspecify.annotations.Nullable;

import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The recipes the simulation runs, read from the recipe files the server loaded (the same ones the machines inside the
 * buildings run): Basin recipes (pressing into a Basin, mixing), and smelting and blasting for the Smelter. A recipe is
 * plain data here: items by key with counts, fluids in mB, its results and its processing time. Read on the server
 * thread when the server starts and whenever datapacks reload.
 */
public final class Recipes {
    /** One recipe file, read. */
    public record Parsed(String id, String type, List<String> ingredients, Map<String, Long> fluidsIn, Map<String, Long> results,
                         Map<String, Long> fluidsOut, int time) {
    }

    private static final Map<String, Parsed> ALL = new HashMap<>();
    /** Smelting (and blasting) by the item it takes. */
    private static final Map<String, Parsed> SMELT = new HashMap<>();
    /** Items of each tag an ingredient names, by "#tag". */
    private static final Map<String, List<String>> TAGS = new HashMap<>();
    private static boolean loaded;
    /** Droplets in a mB (Fabric's 81000 a bucket). */
    private static final long DROPLETS = 81;

    private Recipes() {
    }

    static synchronized void forget() {
        ALL.clear();
        SMELT.clear();
        TAGS.clear();
        loaded = false;
    }

    public static synchronized void load(MinecraftServer server) {
        if (loaded) return;
        loaded = true;
        Set<String> live = new HashSet<>();
        for (var holder : server.getRecipeManager().getRecipes()) live.add(holder.id().identifier().toString());
        int n = 0;
        for (Map.Entry<Identifier, Resource> e : server.getResourceManager().listResources("recipe", id -> id.getPath().endsWith(".json")).entrySet()) {
            Identifier file = e.getKey();
            String path = file.getPath();
            String id = file.getNamespace() + ":" + path.substring("recipe/".length(), path.length() - ".json".length());
            if (!live.contains(id)) continue;
            try (Reader reader = e.getValue().openAsReader()) {
                JsonElement json = JsonParser.parseReader(reader);
                if (!json.isJsonObject()) continue;
                Parsed p = parse(id, json.getAsJsonObject());
                if (p == null) continue;
                ALL.put(id, p);
                n++;
                if ((p.type.equals("minecraft:smelting") || p.type.equals("minecraft:blasting")) && !p.ingredients.isEmpty()) {
                    for (String item : expand(p.ingredients.getFirst())) {
                        Parsed before = SMELT.get(item);
                        // the fan's blasting takes blasting recipes first
                        if (before == null || p.type.equals("minecraft:blasting") && !before.type.equals("minecraft:blasting")) SMELT.put(item, p);
                    }
                }
            } catch (Exception ex) {
                Siftec.LOGGER.debug("SIFTEC: could not read recipe {} for the simulation", id, ex);
            }
        }
        Siftec.LOGGER.info("SIFTEC: the factory simulation read {} recipes ({} smelting inputs)", n, SMELT.size());
    }

    public static @Nullable Parsed get(String id) {
        return ALL.get(id);
    }

    public static @Nullable Parsed smelting(String itemKey) {
        return SMELT.get(Keys.id(itemKey));
    }

    /** Every recipe of a type ("create:compacting") whose results include the item. */
    public static List<Parsed> making(String type, String itemId) {
        List<Parsed> out = new ArrayList<>();
        for (Parsed p : ALL.values()) if (p.type.equals(type) && p.results.containsKey(itemId)) out.add(p);
        out.sort(java.util.Comparator.comparing(Parsed::id));
        return out;
    }

    public static List<Parsed> ofType(String type) {
        List<Parsed> out = new ArrayList<>();
        for (Parsed p : ALL.values()) if (p.type.equals(type)) out.add(p);
        out.sort(java.util.Comparator.comparing(Parsed::id));
        return out;
    }

    /** The item ids an ingredient key stands for: itself, or the items of its tag. */
    public static List<String> expand(String ingredient) {
        if (!ingredient.startsWith("#")) return List.of(ingredient);
        List<String> known = TAGS.get(ingredient);
        if (known != null) return known;
        List<String> items = new ArrayList<>();
        Identifier tag = Identifier.tryParse(ingredient.substring(1));
        if (tag != null) {
            for (Holder<Item> h : BuiltInRegistries.ITEM.getTagOrEmpty(TagKey.create(Registries.ITEM, tag))) {
                items.add(BuiltInRegistries.ITEM.getKey(h.value()).toString());
            }
        }
        TAGS.put(ingredient, items);
        return items;
    }

    // ------------------------------------------------------------------------------------------------ reading

    private static @Nullable Parsed parse(String id, JsonObject o) {
        String type = o.has("type") ? o.get("type").getAsString() : "";
        List<String> ingredients = new ArrayList<>();
        Map<String, Long> fluidsIn = new LinkedHashMap<>(), results = new LinkedHashMap<>(), fluidsOut = new LinkedHashMap<>();
        if (o.has("ingredients")) {
            for (JsonElement e : o.getAsJsonArray("ingredients")) {
                if (e.isJsonObject() && e.getAsJsonObject().has("fluid")) {
                    fluid(e.getAsJsonObject(), fluidsIn);
                    continue;
                }
                int count = e.isJsonObject() && e.getAsJsonObject().has("count") ? e.getAsJsonObject().get("count").getAsInt() : 1;
                String key = ingredient(e);
                if (key == null) return null;
                for (int i = 0; i < count; i++) ingredients.add(key);
            }
        }
        if (o.has("ingredient")) {
            String key = ingredient(o.get("ingredient"));
            if (key == null) return null;
            ingredients.add(key);
        }
        if (o.has("fluid_ingredients")) for (JsonElement e : o.getAsJsonArray("fluid_ingredients")) if (e.isJsonObject()) fluid(e.getAsJsonObject(), fluidsIn);
        if (o.has("results")) {
            for (JsonElement e : o.getAsJsonArray("results")) {
                if (!e.isJsonObject()) continue;
                JsonObject r = e.getAsJsonObject();
                // chance results are left out: the simulation only counts what is sure
                if (r.has("chance") && r.get("chance").getAsFloat() < 1f) continue;
                if (r.has("fluid")) {
                    fluid(r, fluidsOut);
                    continue;
                }
                String item = r.has("id") ? r.get("id").getAsString() : r.has("item") ? r.get("item").getAsString() : null;
                if (item == null) continue;
                results.merge(item, (long) (r.has("count") ? r.get("count").getAsInt() : 1), Long::sum);
            }
        }
        if (o.has("result")) {
            JsonElement r = o.get("result");
            if (r.isJsonPrimitive()) results.merge(r.getAsString(), 1L, Long::sum);
            else if (r.isJsonObject()) {
                JsonObject ro = r.getAsJsonObject();
                String item = ro.has("id") ? ro.get("id").getAsString() : ro.has("item") ? ro.get("item").getAsString() : null;
                if (item != null) results.merge(item, (long) (ro.has("count") ? ro.get("count").getAsInt() : 1), Long::sum);
            }
        }
        int time = o.has("processing_time") ? o.get("processing_time").getAsInt() : o.has("cookingtime") ? o.get("cookingtime").getAsInt() : 0;
        return new Parsed(id, type, ingredients, fluidsIn, results, fluidsOut, time);
    }

    /** An item ingredient as a key: an item id, or "#tag"; the first choice of a list. */
    private static @Nullable String ingredient(JsonElement e) {
        if (e.isJsonPrimitive()) return e.getAsString();
        if (e.isJsonArray()) {
            JsonArray a = e.getAsJsonArray();
            return a.isEmpty() ? null : ingredient(a.get(0));
        }
        if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            if (o.has("item")) return o.get("item").getAsString();
            if (o.has("id")) return o.get("id").getAsString();
            if (o.has("tag")) return "#" + o.get("tag").getAsString();
            if (o.has("items")) return ingredient(o.get("items"));
        }
        return null;
    }

    private static void fluid(JsonObject f, Map<String, Long> into) {
        if (!f.has("fluid")) return;
        String fluid = f.get("fluid").getAsString();
        long amount = f.has("amount") ? f.get("amount").getAsLong() : 81000;
        into.merge("fluid:" + fluid, Math.max(1, amount / DROPLETS), Long::sum);
    }
}
