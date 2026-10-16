package com.meakaandre.siftec.client.map;

import com.meakaandre.siftec.map.MapMarker;
import com.meakaandre.siftec.map.MapPoi;
import com.meakaandre.siftec.node.NodeType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The fullscreen map's filter panel, as Satisfactory's (without vehicles): a switch for each category, and inside
 * most of them a switch for each kind. A switch that is off hides its things on the map, the minimap and the compass.
 */
public final class MapFilters {
    /**
     * One switch.
     *
     * @param key    what {@link MapClient#HIDDEN} holds while it is off
     * @param parent the category switch it sits under, or null for a category
     */
    public record Filter(String key, Component label, @Nullable String parent) {
    }

    public static final List<Filter> ALL = new ArrayList<>();

    static {
        for (MapPoi.Category c : MapPoi.Category.values()) {
            String cat = cat(c);
            ALL.add(new Filter(cat, Component.translatable(c.key()), null));
            switch (c) {
                case MARKERS -> {
                    ALL.add(new Filter("markers:placed", Component.translatable("siftec.map.filter.placed"), cat));
                    ALL.add(new Filter("markers:death", Component.translatable("siftec.map.filter.deaths"), cat));
                }
                case NODES, WELLS -> {
                    for (NodeType t : NodeType.values()) {
                        if (t.dimension() != Level.OVERWORLD || t.isFluid() != (c == MapPoi.Category.WELLS)) continue;
                        ALL.add(new Filter("node:" + t.id(), Component.translatable(t.key()), cat));
                    }
                }
                case ARTIFACTS, BUILDINGS -> {
                    for (MapPoi.Kind k : MapPoi.Kind.values()) {
                        if (k.category == c) ALL.add(new Filter(kind(k), Component.translatable(k.key()), cat));
                    }
                }
                default -> {
                }
            }
        }
    }

    private MapFilters() {
    }

    public static String cat(MapPoi.Category c) {
        return "cat:" + c.name().toLowerCase(Locale.ROOT);
    }

    public static String kind(MapPoi.Kind k) {
        return "kind:" + k.name().toLowerCase(Locale.ROOT);
    }

    public static boolean on(String key) {
        return !MapClient.HIDDEN.contains(key);
    }

    public static void toggle(String key) {
        if (!MapClient.HIDDEN.remove(key)) MapClient.HIDDEN.add(key);
        MapClient.saveSettings();
    }

    public static boolean shows(MapPoi poi) {
        if (!on(cat(poi.kind().category))) return false;
        if (poi.kind() == MapPoi.Kind.NODE || poi.kind() == MapPoi.Kind.WELL) return on("node:" + poi.sub());
        return on(kind(poi.kind()));
    }

    public static boolean shows(MapMarker m) {
        return on(cat(MapPoi.Category.MARKERS)) && on(m.death ? "markers:death" : "markers:placed");
    }

    public static boolean showsPlayers() {
        return on(cat(MapPoi.Category.PLAYERS));
    }
}
