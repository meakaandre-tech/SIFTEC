package com.meakaandre.siftec.company;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.save.JsonSavedData;
import com.mojang.serialization.Codec;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** All companies of a world. Kept as plain objects so new fields never need a migration; saved as an NBT tree. */
public class CompanyData extends JsonSavedData<CompanyData.Stored> {
    public static class Stored {
        Map<String, Company> companies = new HashMap<>();
        /** Players who already got their starting HUB. */
        Set<String> gotHub = new HashSet<>();
        /** Companies that were folded into another one: old id -> the company that took it over. */
        Map<String, String> moved = new HashMap<>();
    }

    public static final Codec<CompanyData> CODEC = JsonSavedData.codec(CompanyData::new);
    private static final SavedDataType<CompanyData> TYPE = new SavedDataType<>(
        Siftec.id("companies"), CompanyData::new, CODEC, null
    );

    public CompanyData() {
        super("companies", Stored.class, Stored::new);
    }

    public static CompanyData get(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(TYPE);
    }

    public Map<String, Company> companies() {
        return stored.companies;
    }

    public Set<String> gotHub() {
        return stored.gotHub;
    }

    /** Follows a company that was folded into another, so what it built keeps working. */
    public Company byId(String id) {
        for (int hops = 0; hops < 8 && id != null; hops++) {
            Company company = stored.companies.get(id);
            if (company != null) return company;
            id = stored.moved.get(id);
        }
        return null;
    }

    /** Everything the old company owned now answers to the new one. */
    public void fold(String oldId, String newId) {
        stored.companies.remove(oldId);
        stored.moved.put(oldId, newId);
        setDirty();
    }

    public Company ofMember(String playerId) {
        for (Company company : stored.companies.values()) {
            if (company.members.contains(playerId)) return company;
        }
        return null;
    }
}
