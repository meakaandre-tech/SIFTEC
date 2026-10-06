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

    private Milestones() {
    }

    public static void load() {
        for (int i = 0; i < TIERS; i++) BY_TIER.add(new ArrayList<>());
        try (var in = Siftec.class.getResourceAsStream("/siftec_content.json")) {
            JsonObject root = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
            for (JsonElement e : root.getAsJsonArray("parts")) PART_IDS.add(e.getAsJsonObject().get("id").getAsString());
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
        } catch (Exception e) {
            throw new IllegalStateException("SIFTEC could not read siftec_content.json", e);
        }
    }

    private static Milestone read(JsonObject o, int index) {
        List<Milestone.Cost> cost = new ArrayList<>();
        for (JsonElement c : o.getAsJsonArray("cost")) {
            JsonObject co = c.getAsJsonObject();
            cost.add(new Milestone.Cost(Identifier.parse(co.get("item").getAsString()), co.get("count").getAsInt()));
        }
        List<Identifier> items = new ArrayList<>();
        for (JsonElement i : o.getAsJsonArray("items")) items.add(Identifier.parse(i.getAsString()));
        List<String> tokens = new ArrayList<>();
        JsonArray t = o.getAsJsonArray("tokens");
        for (JsonElement i : t) tokens.add(i.getAsString());
        return new Milestone(o.get("id").getAsString(), o.get("tier").getAsInt(), index, cost, o.get("seconds").getAsInt(), items, tokens);
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
