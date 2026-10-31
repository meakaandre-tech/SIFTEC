package com.meakaandre.siftec.building;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.hub.Milestones;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Build Gun's build menu, laid out like Satisfactory 1.0's: categories (Special, Production, Power, ...), each with
 * subcategories, from {@code siftec_content.json} (tools/tables/content.py, BUILD_MENU). Only buildings the player's
 * company has unlocked are shown, as in Satisfactory, and the search box filters them by name. The client draws it
 * (client/BuildMenuScreen.java); the rules are here so the server's selftests check the same ones.
 */
public final class BuildMenu {
    /** Where a building sits in the menu. */
    public record Place(String section, String sub) {
    }

    private static final List<String> SECTIONS = new ArrayList<>(), SUBS = new ArrayList<>(), HOTBAR = new ArrayList<>();
    private static final Map<String, Place> PLACES = new HashMap<>();
    private static boolean loaded;

    private BuildMenu() {
    }

    private static synchronized void load() {
        if (loaded) return;
        JsonObject raw = Milestones.raw();
        if (raw == null) return;
        loaded = true;
        JsonObject menu = raw.getAsJsonObject("build_menu");
        if (menu != null) {
            for (JsonElement e : menu.getAsJsonArray("sections")) SECTIONS.add(e.getAsString());
            for (JsonElement e : menu.getAsJsonArray("subs")) SUBS.add(e.getAsString());
            for (JsonElement e : menu.getAsJsonArray("hotbar")) HOTBAR.add(e.getAsString());
        }
        for (JsonElement e : raw.getAsJsonArray("buildings")) {
            JsonObject o = e.getAsJsonObject();
            if (!o.has("menu")) continue;
            var a = o.getAsJsonArray("menu");
            PLACES.put(o.get("id").getAsString(), new Place(a.get(0).getAsString(), a.get(1).getAsString()));
        }
    }

    /** The categories, in the menu's order. */
    public static List<String> sections() {
        load();
        return List.copyOf(SECTIONS);
    }

    /** What a new player's first build hotbar holds, slot after slot. */
    public static List<String> defaultHotbar() {
        load();
        return List.copyOf(HOTBAR);
    }

    /** Where the building sits in the menu; a building the table misses goes under its data tab. */
    public static Place place(BuildingDef def) {
        load();
        Place p = PLACES.get(def.id());
        return p != null ? p : new Place(def.category().name().toLowerCase(Locale.ROOT), "other");
    }

    public static Component sectionName(String section) {
        return Component.translatable("siftec.build_menu.section." + section);
    }

    public static Component subName(String sub) {
        return Component.translatableWithFallback("siftec.build_menu.sub." + sub, sub);
    }

    /** True if the search text (any case, spaces ignored at the ends) is in the building's name or id; an empty search matches all. */
    public static boolean matches(BuildingDef def, String query) {
        String q = query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return true;
        return def.name().getString().toLowerCase(Locale.ROOT).contains(q) || def.id().replace('_', ' ').contains(q) || def.id().contains(q);
    }

    /**
     * The buildings the menu shows: only the unlocked ones (by id), those matching the search, in menu order (category,
     * then subcategory, then the content table's order).
     */
    public static List<BuildingDef> visible(Collection<String> unlocked, String query) {
        load();
        List<BuildingDef> out = new ArrayList<>();
        for (BuildingDef def : Buildings.all()) if (unlocked.contains(def.id()) && matches(def, query)) out.add(def);
        out.sort(Comparator.comparingInt((BuildingDef d) -> order(SECTIONS, place(d).section())).thenComparingInt(d -> order(SUBS, place(d).sub())));
        return out;
    }

    private static int order(List<String> list, String key) {
        int i = list.indexOf(key);
        return i < 0 ? list.size() : i;
    }

    /**
     * The ids of the buildings a player of this company may see in the menu: every one not locked for the company, dev
     * buildings only for creative players, and all of them for creative players.
     */
    public static List<String> unlocked(Company company, boolean creative) {
        List<String> out = new ArrayList<>();
        for (BuildingDef def : Buildings.all()) {
            if (def.dev() && !creative) continue;
            if (creative || !Buildings.locked(def, company)) out.add(def.id());
        }
        return out;
    }
}
