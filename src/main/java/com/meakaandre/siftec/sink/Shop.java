package com.meakaandre.siftec.sink;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.hub.Milestone;
import com.meakaandre.siftec.hub.Milestones;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The AWESOME Shop's catalogue (AWESOME Sink and Shop tab): cosmetics only, bought with FICSIT Coupons and unlocked for
 * the whole company. What an entry gives: the Customizer, a colour swatch slot, a paint finish or casing skin for the
 * Customizer, or a decoration or cup that members then take from the Shop for its part cost.
 */
public final class Shop {
    /** The most colour swatches a company can have. */
    public static final int MAX_SWATCHES = 18;

    /** One thing the Shop sells. {@code gives} is "customizer", "swatch", "finish:<id>", "skin:<id>", "decor:<id>" or "cup:<id>". */
    public record Entry(String id, String category, int coupons, String gives) {
        public String kind() {
            int colon = gives.indexOf(':');
            return colon < 0 ? gives : gives.substring(0, colon);
        }

        public String arg() {
            int colon = gives.indexOf(':');
            return colon < 0 ? "" : gives.substring(colon + 1);
        }

        /** True if the company has it (a swatch slot is never "had": more can be bought until the company has them all). */
        public boolean owned(Company company) {
            if (coupons == 0) return true;
            if (kind().equals("swatch")) return company.swatches.size() >= MAX_SWATCHES;
            return company.shop.contains(id);
        }
    }

    /** A decoration or cup: what taking one costs, and what has to be done first ("" for nothing). */
    public record Goods(String id, List<Milestone.Cost> cost, String needs) {
    }

    private static final List<Entry> ENTRIES = new ArrayList<>();
    private static final Map<String, Goods> GOODS = new LinkedHashMap<>();
    private static final Map<String, String> FINISHES = new LinkedHashMap<>(), SKINS = new LinkedHashMap<>(), STORAGE_SKINS = new LinkedHashMap<>();
    private static final List<String> DYES = new ArrayList<>();
    private static boolean loaded;

    private Shop() {
    }

    private static List<Milestone.Cost> costs(JsonObject o) {
        List<Milestone.Cost> out = new ArrayList<>();
        for (JsonElement c : o.getAsJsonArray("cost")) out.add(new Milestone.Cost(c.getAsJsonObject().get("item").getAsString(), c.getAsJsonObject().get("count").getAsInt()));
        return out;
    }

    private static synchronized void load() {
        if (loaded) return;
        JsonObject raw = Milestones.raw();
        for (JsonElement e : raw.getAsJsonArray("shop")) {
            JsonObject o = e.getAsJsonObject();
            ENTRIES.add(new Entry(o.get("id").getAsString(), o.get("category").getAsString(), o.get("coupons").getAsInt(), o.get("gives").getAsString()));
        }
        for (JsonElement e : raw.getAsJsonArray("decor")) {
            JsonObject o = e.getAsJsonObject();
            GOODS.put(o.get("id").getAsString(), new Goods(o.get("id").getAsString(), costs(o), ""));
        }
        for (JsonElement e : raw.getAsJsonArray("cups")) {
            JsonObject o = e.getAsJsonObject();
            GOODS.put(o.get("id").getAsString(), new Goods(o.get("id").getAsString(), costs(o), o.get("needs").getAsString()));
        }
        for (Map.Entry<String, JsonElement> e : raw.getAsJsonObject("finishes").entrySet()) FINISHES.put(e.getKey(), e.getValue().getAsString());
        for (Map.Entry<String, JsonElement> e : raw.getAsJsonObject("skins").entrySet()) SKINS.put(e.getKey(), e.getValue().getAsString());
        for (Map.Entry<String, JsonElement> e : raw.getAsJsonObject("storage_skins").entrySet()) STORAGE_SKINS.put(e.getKey(), e.getValue().getAsString());
        for (JsonElement e : raw.getAsJsonArray("dyes")) DYES.add(e.getAsString());
        loaded = true;
    }

    public static List<Entry> entries() {
        load();
        return List.copyOf(ENTRIES);
    }

    public static @Nullable Entry entry(String id) {
        for (Entry e : entries()) if (e.id().equals(id)) return e;
        return null;
    }

    public static @Nullable Goods goods(String id) {
        load();
        return GOODS.get(id);
    }

    /** Paint finishes: id -> the block the primary panels become ("{colour}" stands for the swatch's primary colour). */
    public static Map<String, String> finishes() {
        load();
        return FINISHES;
    }

    /** Casing skins for every building: id -> the block the light grey panels become. */
    public static Map<String, String> skins() {
        load();
        return SKINS;
    }

    /** Skins for Storage Containers only. */
    public static Map<String, String> storageSkins() {
        load();
        return STORAGE_SKINS;
    }

    /** The sixteen dye colours a swatch can use. */
    public static List<String> dyes() {
        load();
        return DYES;
    }

    /** True if the company may use this finish ("painted" always) or skin ("" always). */
    public static boolean hasFinish(Company company, String finish) {
        return finish.equals("painted") || owns(company, "finish:" + finish);
    }

    public static boolean hasSkin(Company company, String skin) {
        return skin.isEmpty() || owns(company, "skin:" + skin);
    }

    /** True if the company has bought the entry that gives this. */
    public static boolean owns(Company company, String gives) {
        for (Entry e : entries()) if (e.gives().equals(gives)) return e.owned(company);
        return false;
    }

    /** What happens when the company buys an entry (the coupons are already paid): it is remembered, or a swatch added. */
    public static void unlock(Company company, Entry entry) {
        if (entry.kind().equals("swatch")) {
            if (company.swatches.size() < MAX_SWATCHES) company.swatches.add(company.swatches.getFirst());
        } else {
            company.shop.add(entry.id());
        }
    }
}
