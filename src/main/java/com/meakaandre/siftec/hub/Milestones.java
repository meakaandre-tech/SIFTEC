package com.meakaandre.siftec.hub;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.company.Company;
import net.minecraft.resources.Identifier;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Every milestone and wormhole phase, read from siftec_content.json (written by tools/gen_assets.py). */
public final class Milestones {
    public static final int TIERS = 10;
    private static final Map<String, Milestone> BY_ID = new LinkedHashMap<>();
    private static final List<List<Milestone>> BY_TIER = new ArrayList<>();
    private static final List<Milestone> PHASES = new ArrayList<>();
    private static final List<String> PART_IDS = new ArrayList<>();
    private static final List<Identifier> DISABLED = new ArrayList<>();
    private static final java.util.Set<Identifier> REMOVED_RECIPES = new java.util.HashSet<>();
    private static final List<FluidDef> FLUIDS = new ArrayList<>();
    private static final List<Build> WORKSHOP = new ArrayList<>();
    private static final List<Tree> TREES = new ArrayList<>();
    private static JsonObject raw;

    /** The whole content file, for the smaller tables other classes read themselves. */
    public static JsonObject raw() {
        return raw;
    }

    /** A MAM research tree. */
    public record Tree(String id, Identifier icon, List<Milestone> nodes) {
    }

    public record FluidDef(String id, int color) {
    }

    /** Something the Equipment Workshop builds. */
    public record Build(Identifier item, List<Milestone.Cost> cost) {
    }

    private Milestones() {
    }

    public static void load() {
        for (int i = 0; i < TIERS; i++) BY_TIER.add(new ArrayList<>());
        try (var in = Siftec.class.getResourceAsStream("/siftec_content.json")) {
            JsonObject root = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
            raw = root;
            for (JsonElement e : root.getAsJsonArray("parts")) PART_IDS.add(e.getAsJsonObject().get("id").getAsString());
            for (JsonElement e : root.getAsJsonArray("removed_recipes")) REMOVED_RECIPES.add(Identifier.parse(e.getAsString()));
            for (JsonElement e : root.getAsJsonArray("fluids")) {
                FLUIDS.add(new FluidDef(e.getAsJsonObject().get("id").getAsString(), e.getAsJsonObject().get("color").getAsInt()));
            }
            for (JsonElement e : root.getAsJsonArray("workshop")) {
                JsonObject o = e.getAsJsonObject();
                List<Milestone.Cost> cost = new ArrayList<>();
                for (JsonElement c : o.getAsJsonArray("cost")) {
                    cost.add(new Milestone.Cost(c.getAsJsonObject().get("item").getAsString(), c.getAsJsonObject().get("count").getAsInt()));
                }
                WORKSHOP.add(new Build(Identifier.parse(o.get("item").getAsString()), cost));
            }
            for (JsonElement e : root.getAsJsonArray("disabled")) DISABLED.add(Identifier.parse(e.getAsString()));
            for (JsonElement e : root.getAsJsonArray("milestones")) {
                JsonObject o = e.getAsJsonObject();
                List<Milestone> tier = BY_TIER.get(o.get("tier").getAsInt());
                Milestone m = read(o, tier.size());
                tier.add(m);
                BY_ID.put(m.id(), m);
            }
            for (JsonElement e : root.getAsJsonArray("phases")) {
                Milestone m = read(e.getAsJsonObject(), PHASES.size());
                PHASES.add(m);
                BY_ID.put(m.id(), m);
            }
            java.util.Map<String, List<Milestone>> byTree = new java.util.HashMap<>();
            for (JsonElement e : root.getAsJsonArray("milestones_mam")) {
                JsonObject o = e.getAsJsonObject();
                List<Milestone> nodes = byTree.computeIfAbsent(o.get("tree").getAsString(), k -> new ArrayList<>());
                Milestone base = read(o, nodes.size());
                List<String> needs = new ArrayList<>();
                for (JsonElement n : o.getAsJsonArray("needs")) needs.add(n.getAsString());
                Milestone m = new Milestone(base.id(), 100, base.index(), base.cost(), base.seconds(), base.items(), base.tokens(), o.get("tree").getAsString(), needs);
                nodes.add(m);
                BY_ID.put(m.id(), m);
            }
            for (JsonElement e : root.getAsJsonArray("trees")) {
                JsonObject o = e.getAsJsonObject();
                TREES.add(new Tree(o.get("id").getAsString(), Identifier.parse(o.get("icon").getAsString()), byTree.getOrDefault(o.get("id").getAsString(), List.of())));
            }
        } catch (Exception e) {
            throw new IllegalStateException("SIFTEC could not read siftec_content.json", e);
        }
    }

    private static Milestone read(JsonObject o, int index) {
        List<Milestone.Cost> cost = new ArrayList<>();
        for (JsonElement c : o.getAsJsonArray("cost")) {
            JsonObject co = c.getAsJsonObject();
            cost.add(new Milestone.Cost(co.get("item").getAsString(), co.get("count").getAsInt()));
        }
        List<Identifier> items = new ArrayList<>();
        for (JsonElement i : o.getAsJsonArray("items")) items.add(Identifier.parse(i.getAsString()));
        List<String> tokens = new ArrayList<>();
        JsonArray t = o.getAsJsonArray("tokens");
        for (JsonElement i : t) tokens.add(i.getAsString());
        return new Milestone(o.get("id").getAsString(), o.has("tier") ? o.get("tier").getAsInt() : 100, index, cost, o.get("seconds").getAsInt(), items, tokens);
    }

    public static java.util.Set<Identifier> removedRecipes() {
        return REMOVED_RECIPES;
    }

    public static List<FluidDef> fluids() {
        return FLUIDS;
    }

    public static List<Build> workshop() {
        return WORKSHOP;
    }

    public static List<Identifier> disabled() {
        return DISABLED;
    }

    public static List<String> partIds() {
        return PART_IDS;
    }

    public static Milestone get(String id) {
        return BY_ID.get(id);
    }

    public static Iterable<Milestone> all() {
        return BY_ID.values();
    }

    public static List<Milestone> tier(int tier) {
        return BY_TIER.get(tier);
    }

    public static List<Tree> trees() {
        return TREES;
    }

    public static List<Milestone> phases() {
        return PHASES;
    }

    /** The wormhole phase that opens a tier, or 0 when the tier needs none. */
    public static int phaseFor(int tier) {
        return tier <= 2 ? 0 : tier <= 4 ? 1 : tier <= 6 ? 2 : tier <= 8 ? 3 : 4;
    }

    public static boolean tierOpen(Company company, int tier) {
        if (tier == 0) return true;
        if (!company.has("hub_upgrade_6")) return false;
        int phase = phaseFor(tier);
        return phase == 0 || company.has("phase_" + phase);
    }

    /** What still has to be done before this one can be paid for, or null when it is open. */
    public static Milestone blocker(Company company, Milestone m) {
        if (m.isResearch()) {
            for (String need : m.needs()) if (!company.has(need)) return get(need);
            return null;
        }
        if (m.isPhase()) {
            if (!company.has("hub_upgrade_6")) return get("hub_upgrade_6");
            return m.index() > 0 && !company.has(PHASES.get(m.index() - 1).id()) ? PHASES.get(m.index() - 1) : null;
        }
        if (m.tier() == 0) {
            return m.index() > 0 && !company.has(tier(0).get(m.index() - 1).id()) ? tier(0).get(m.index() - 1) : null;
        }
        if (!company.has("hub_upgrade_6")) return get("hub_upgrade_6");
        int phase = phaseFor(m.tier());
        return phase > 0 && !company.has("phase_" + phase) ? PHASES.get(phase - 1) : null;
    }
}
