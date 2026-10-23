package com.meakaandre.siftec.map;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.save.JsonSavedData;
import com.mojang.serialization.Codec;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.List;

/** Every map marker of the world: private ones and company ones, and death markers. */
public class MapMarkers extends JsonSavedData<MapMarkers.Stored> {
    public static class Stored {
        List<MapMarker> markers = new ArrayList<>();
    }

    /** Death markers kept per player: the oldest goes when there are more. */
    public static final int DEATHS_KEPT = 3;
    /** Markers a player may have placed (private and company together). */
    public static final int MAX_PER_PLAYER = 200;

    public static final Codec<MapMarkers> CODEC = JsonSavedData.codec(MapMarkers::new);
    private static final SavedDataType<MapMarkers> TYPE = new SavedDataType<>(Siftec.id("map_markers"), MapMarkers::new, CODEC, null);

    public MapMarkers() {
        super("map_markers", Stored.class, Stored::new);
    }

    public static MapMarkers get(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(TYPE);
    }

    public List<MapMarker> all() {
        return stored.markers;
    }

    /** True if a member of this company (or this player) sees the marker. */
    public static boolean visible(MapMarker m, String player, String companyId) {
        return m.owner.equals(player) || (m.company && Companies.same(m.companyId, companyId));
    }

    public List<MapMarker> visibleTo(String player, String companyId) {
        List<MapMarker> out = new ArrayList<>();
        for (MapMarker m : stored.markers) if (visible(m, player, companyId)) out.add(m);
        return out;
    }

    public MapMarker byId(String id) {
        for (MapMarker m : stored.markers) if (m.id.equals(id)) return m;
        return null;
    }

    public int countOf(String player) {
        int n = 0;
        for (MapMarker m : stored.markers) if (m.owner.equals(player) && !m.death) n++;
        return n;
    }
}
