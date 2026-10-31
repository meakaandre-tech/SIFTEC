package com.meakaandre.siftec.building;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.save.JsonSavedData;
import com.mojang.serialization.Codec;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Every player's ten build hotbars of ten slots (Satisfactory's), kept for the whole world: what the build menu
 * bound to each slot, and which bar was last shown. A new player's first bar starts with {@link BuildMenu#defaultHotbar()}.
 */
public final class BuildHotbars {
    public static final int BARS = 10, SLOTS = 10;

    /** One player's bars: {@link #BARS} times {@link #SLOTS} building ids ("" for an empty slot), bar after bar. */
    public static class Bars {
        public List<String> slots = new ArrayList<>();
        public int page;

        /** Pads or trims the slots to the full count (a file from an older layout, or a new player). */
        void fix() {
            if (slots == null) slots = new ArrayList<>();
            while (slots.size() < BARS * SLOTS) slots.add("");
            while (slots.size() > BARS * SLOTS) slots.removeLast();
            for (int i = 0; i < slots.size(); i++) if (slots.get(i) == null) slots.set(i, "");
            page = Math.floorMod(page, BARS);
        }

        public String get(int bar, int slot) {
            return slots.get(bar * SLOTS + slot);
        }
    }

    public static class Data extends JsonSavedData<Data.Stored> {
        public static class Stored {
            Map<String, Bars> players = new HashMap<>();
        }

        static final Codec<Data> CODEC = JsonSavedData.codec(Data::new);
        static final SavedDataType<Data> TYPE = new SavedDataType<>(Siftec.id("build_hotbars"), Data::new, CODEC, null);

        public Data() {
            super("build_hotbars", Stored.class, Stored::new);
        }

        static Data get(MinecraftServer server) {
            return server.getDataStorage().computeIfAbsent(TYPE);
        }
    }

    private BuildHotbars() {
    }

    /** The player's bars, a new player's made with the default first bar. */
    public static Bars of(MinecraftServer server, UUID player) {
        Data data = Data.get(server);
        Bars bars = data.stored.players.get(player.toString());
        if (bars == null) {
            bars = new Bars();
            bars.fix();
            List<String> first = BuildMenu.defaultHotbar();
            for (int i = 0; i < first.size() && i < SLOTS; i++) bars.slots.set(i, first.get(i));
            data.stored.players.put(player.toString(), bars);
            data.setDirty();
        }
        bars.fix();
        return bars;
    }

    /** True if the player has bars saved (they have held the gun, or bound a slot). */
    public static boolean has(MinecraftServer server, UUID player) {
        return Data.get(server).stored.players.containsKey(player.toString());
    }

    /** Every player's bars, for the selftests. */
    public static Map<String, Bars> all(MinecraftServer server) {
        return Map.copyOf(Data.get(server).stored.players);
    }

    /** Puts a building ("" to empty it) into a slot. False if the bar, slot or building is not one. */
    public static boolean bind(MinecraftServer server, UUID player, int bar, int slot, String building) {
        if (bar < 0 || bar >= BARS || slot < 0 || slot >= SLOTS) return false;
        if (!building.isEmpty() && Buildings.def(building) == null) return false;
        Bars bars = of(server, player);
        bars.slots.set(bar * SLOTS + slot, building);
        Data.get(server).setDirty();
        return true;
    }

    /** Remembers the bar the player switched to. */
    public static void page(MinecraftServer server, UUID player, int bar) {
        Bars bars = of(server, player);
        int page = Math.floorMod(bar, BARS);
        if (bars.page == page) return;
        bars.page = page;
        Data.get(server).setDirty();
    }
}
