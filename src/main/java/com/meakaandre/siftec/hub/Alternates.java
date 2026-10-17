package com.meakaandre.siftec.hub;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.net.CompanyState;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Alternate recipes, the reward for Hard Drives. A researched drive offers two the company does not have
 * yet; picking one unlocks that recipe in the company's machines. An alternate is only offered once the
 * company has unlocked the SIFTEC building that runs it (the Constructor, the Refinery...) and everything it uses, so a
 * drive only offers alternates of the tiers whose buildings the company has.
 */
public final class Alternates {
    /** The research id a Hard Drive runs under, and how long it takes. */
    public static final String RESEARCH = "hard_drive";
    public static final int SECONDS = 600;
    private static final Map<String, List<String>> REQUIRES = new LinkedHashMap<>();
    /** The building that runs each alternate (siftec_content.json "alternates", "building"). */
    private static final Map<String, String> BUILDING = new LinkedHashMap<>();

    private Alternates() {
    }

    private static Map<String, List<String>> all() {
        if (REQUIRES.isEmpty()) {
            for (JsonElement e : Milestones.raw().getAsJsonArray("alternates")) {
                JsonObject o = e.getAsJsonObject();
                List<String> needs = new ArrayList<>();
                for (JsonElement n : o.getAsJsonArray("requires")) needs.add(n.getAsString());
                REQUIRES.put(o.get("id").getAsString(), needs);
                if (o.has("building")) BUILDING.put(o.get("id").getAsString(), o.get("building").getAsString());
            }
        }
        return REQUIRES;
    }

    public static int total() {
        return all().size();
    }

    public static int owned(Company company) {
        int n = 0;
        for (String id : all().keySet()) if (company.has(id)) n++;
        return n;
    }

    public static Component name(String id) {
        return Component.translatable("siftec.alt." + id);
    }

    public static Component text(String id) {
        return Component.translatable("siftec.alt." + id + ".text");
    }

    /** The id of the building that runs this alternate, or "" if none is listed. */
    public static String building(String id) {
        all();
        return BUILDING.getOrDefault(id, "");
    }

    /** True once the company may build the building this alternate runs in. */
    public static boolean buildingUnlocked(Company company, String id) {
        com.meakaandre.siftec.building.BuildingDef def = com.meakaandre.siftec.building.Buildings.def(building(id));
        return def == null || !com.meakaandre.siftec.building.Buildings.locked(def, company);
    }

    /** The alternates this company could be offered right now. */
    public static List<String> eligible(Company company) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : all().entrySet()) {
            if (company.has(entry.getKey())) continue;
            boolean ready = buildingUnlocked(company, entry.getKey());
            for (String need : entry.getValue()) ready &= company.has(need);
            if (ready) out.add(entry.getKey());
        }
        return out;
    }

    /** A drive has finished: put up to two alternates in front of the company. */
    public static void makeOffer(MinecraftServer server, Company company) {
        List<String> pool = eligible(company);
        Collections.shuffle(pool, new Random(server.overworld().getRandom().nextLong()));
        company.offer = new ArrayList<>(pool.subList(0, Math.min(2, pool.size())));
        Companies.save(server);
        Companies.tell(server, company, Component.translatable("siftec.alt.ready").withStyle(ChatFormatting.GOLD));
    }

    public static boolean choose(MinecraftServer server, Company company, String id) {
        if (!company.offer.contains(id)) return false;
        company.done.add(id);
        company.offer = new ArrayList<>();
        Companies.save(server);
        CompanyState.recompute(server);
        Companies.tell(server, company, Component.translatable("siftec.alt.chosen", name(id), text(id)).withStyle(ChatFormatting.GOLD));
        return true;
    }
}
