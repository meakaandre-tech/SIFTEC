package com.meakaandre.siftec.client.map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.hub.Milestone;
import com.meakaandre.siftec.hub.Milestones;
import com.meakaandre.siftec.map.MapGrid;
import com.meakaandre.siftec.map.MapMarker;
import com.meakaandre.siftec.map.MapPayloads;
import com.meakaandre.siftec.map.MapPoi;
import com.meakaandre.siftec.map.MapServer;
import com.meakaandre.siftec.net.ClientState;
import com.mojang.blaze3d.platform.InputConstants;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The map on the client: what the server has sent (fog, POIs, markers, company players), what the player chose
 * (pins, filters, minimap on or off, kept in the game folder) and the keys: M opens the map, N shows or hides the
 * minimap (both changeable under SIFTEC Map).
 */
public final class MapClient {
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Siftec.id("map"));
    public static final KeyMapping OPEN = new KeyMapping("key.siftec.map.open", InputConstants.KEY_M, CATEGORY);
    public static final KeyMapping MINIMAP = new KeyMapping("key.siftec.map.minimap", InputConstants.KEY_N, CATEGORY);

    public static @Nullable String mapId;
    public static final Long2ObjectOpenHashMap<long[]> FOG = new Long2ObjectOpenHashMap<>();
    public static final Long2ObjectOpenHashMap<List<MapPoi>> POIS = new Long2ObjectOpenHashMap<>();
    public static List<MapMarker> markers = List.of();
    public static List<MapPayloads.Mate> mates = List.of();
    /** The company's vehicles and drones (where they are, or would be where their land does not tick). */
    public static List<MapPayloads.Mover> movers = List.of();
    /** Pinned things, by pin key, in the order pinned. */
    public static final Map<String, Pin> PINS = new LinkedHashMap<>();
    /** Filter switches that are off (see {@link MapFilters}). */
    public static final Set<String> HIDDEN = new HashSet<>();
    public static boolean minimapOn = true;
    /** Markers seen since joining, so a new death marker can be pinned at once. */
    private static final Set<String> SEEN = new HashSet<>();
    private static boolean firstMarkers = true;
    private static int ticks;

    /** A pinned thing: shown on the compass with its distance. */
    public record Pin(String key, String label, int x, int y, int z, int icon, int argb) {
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** Tiles are asked for this often (ticks: once a second). */
    private static final int REQUEST_TICKS = 20;
    /** A tile is asked for again at most this often (ms): the player's own region, its neighbours, the open map's. */
    private static final int HERE_EVERY_MS = 8_000, AROUND_EVERY_MS = 20_000, SCREEN_EVERY_MS = 15_000;
    /** Regions asked for in one request at most. */
    private static final int REGIONS_PER_REQUEST = 24;

    private MapClient() {
    }

    public static void register() {
        KeyMappingHelper.registerKeyMapping(OPEN);
        KeyMappingHelper.registerKeyMapping(MINIMAP);
        ClientPlayNetworking.registerGlobalReceiver(MapPayloads.Hello.TYPE, (p, c) -> Minecraft.getInstance().execute(() -> hello(p.mapId())));
        ClientPlayNetworking.registerGlobalReceiver(MapPayloads.Fog.TYPE, (p, c) -> Minecraft.getInstance().execute(() -> fog(p)));
        ClientPlayNetworking.registerGlobalReceiver(MapPayloads.FogDelta.TYPE, (p, c) -> Minecraft.getInstance().execute(() -> fogDelta(p.chunks())));
        ClientPlayNetworking.registerGlobalReceiver(MapPayloads.Pois.TYPE, (p, c) -> Minecraft.getInstance().execute(() -> {
            for (MapPayloads.RegionPois r : p.regions()) POIS.put(r.region(), r.pois());
        }));
        ClientPlayNetworking.registerGlobalReceiver(MapPayloads.Tile.TYPE, (p, c) -> Minecraft.getInstance().execute(() -> ClientTiles.accept(p.region(), p.data())));
        ClientPlayNetworking.registerGlobalReceiver(MapPayloads.Markers.TYPE, (p, c) -> Minecraft.getInstance().execute(() -> markers(p.markers())));
        ClientPlayNetworking.registerGlobalReceiver(MapPayloads.Mates.TYPE, (p, c) -> Minecraft.getInstance().execute(() -> mates = p.mates()));
        ClientPlayNetworking.registerGlobalReceiver(MapPayloads.Movers.TYPE, (p, c) -> Minecraft.getInstance().execute(() -> movers = p.movers()));
        ClientTickEvents.END_CLIENT_TICK.register(MapClient::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(MapClient::reset));
        loadSettings();
        MapHud.register();
    }

    /** True once the company has done Field Research. */
    public static boolean unlocked() {
        Set<String> done = ClientState.done;
        if (done == null) return false;
        for (String id : done) {
            Milestone m = Milestones.get(id);
            if (m != null && m.tokens().contains(MapServer.TOKEN)) return true;
        }
        return false;
    }

    /** True while the player is somewhere the map covers (the Overworld). */
    public static boolean mappedHere() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null && mc.level.dimension() == Level.OVERWORLD;
    }

    public static boolean explored(int x, int z) {
        long[] bits = FOG.get(MapGrid.regionOfBlock(x, z));
        return bits != null && MapGrid.get(bits, MapGrid.fogBit(x >> 4, z >> 4));
    }

    private static void reset() {
        mapId = null;
        FOG.clear();
        POIS.clear();
        markers = List.of();
        mates = List.of();
        movers = List.of();
        PINS.clear();
        SEEN.clear();
        firstMarkers = true;
        ClientTiles.clear();
    }

    private static void hello(String id) {
        if (!id.equals(mapId)) {
            ClientTiles.clear();
            PINS.clear();
        }
        mapId = id;
        loadPins();
    }

    private static void fog(MapPayloads.Fog p) {
        if (p.replace()) {
            for (long region : FOG.keySet()) ClientTiles.fogChanged(region);
            FOG.clear();
            POIS.clear();
        }
        for (MapPayloads.FogRegion r : p.regions()) {
            FOG.put(r.region(), r.bits());
            ClientTiles.fogChanged(r.region());
        }
    }

    private static void fogDelta(long[] chunks) {
        for (long c : chunks) {
            int cx = MapGrid.keyX(c), cz = MapGrid.keyZ(c);
            long region = MapGrid.regionOfChunk(cx, cz);
            long[] bits = FOG.computeIfAbsent(region, k -> new long[MapGrid.FOG_LONGS]);
            MapGrid.set(bits, MapGrid.fogBit(cx, cz));
            ClientTiles.fogChanged(region);
        }
    }

    private static void markers(List<MapMarker> list) {
        Minecraft mc = Minecraft.getInstance();
        String me = mc.player == null ? "" : mc.player.getUUID().toString();
        for (MapMarker m : list) {
            // a death marker that is new since joining is pinned at once, so the way back shows on the compass
            if (SEEN.add(m.id) && !firstMarkers && m.death && m.owner.equals(me)) pin(markerPin(m));
        }
        firstMarkers = false;
        markers = list;
        // pins of markers that are gone go too
        Set<String> live = new HashSet<>();
        for (MapMarker m : list) live.add(m.pinKey());
        boolean changed = PINS.keySet().removeIf(k -> k.startsWith("marker:") && !live.contains(k));
        if (changed) savePins();
    }

    // ------------------------------------------------------------------------------------------------ pins

    public static Pin markerPin(MapMarker m) {
        return new Pin(m.pinKey(), m.death ? "" : m.name, m.x, m.y, m.z, m.death ? MapIcons.SKULL : m.icon, MapDraw.markerColour(m));
    }

    public static boolean pinned(String key) {
        return PINS.containsKey(key);
    }

    public static void pin(Pin pin) {
        PINS.put(pin.key(), pin);
        savePins();
    }

    public static void togglePin(Pin pin) {
        if (PINS.remove(pin.key()) == null) PINS.put(pin.key(), pin);
        savePins();
    }

    /** A pin's live position: a marker's from the marker list (it may have been moved or renamed). */
    public static Pin live(Pin pin) {
        if (!pin.key().startsWith("marker:")) return pin;
        for (MapMarker m : markers) if (m.pinKey().equals(pin.key())) return markerPin(m);
        return pin;
    }

    private static @Nullable Path mapDir() {
        if (mapId == null) return null;
        return Minecraft.getInstance().gameDirectory.toPath().resolve("siftec").resolve("map").resolve(mapId);
    }

    private static void loadPins() {
        PINS.clear();
        Path dir = mapDir();
        if (dir == null) return;
        Path file = dir.resolve("pins.json");
        if (!Files.exists(file)) return;
        try {
            JsonArray array = JsonParser.parseString(Files.readString(file)).getAsJsonArray();
            for (JsonElement e : array) {
                JsonObject o = e.getAsJsonObject();
                Pin pin = new Pin(o.get("key").getAsString(), o.get("label").getAsString(), o.get("x").getAsInt(), o.get("y").getAsInt(),
                    o.get("z").getAsInt(), o.get("icon").getAsInt(), o.get("argb").getAsInt());
                PINS.put(pin.key(), pin);
            }
        } catch (IOException | RuntimeException e) {
            Siftec.LOGGER.warn("SIFTEC: unreadable map file {}", file, e);
        }
    }

    private static void savePins() {
        Path dir = mapDir();
        if (dir == null) return;
        JsonArray array = new JsonArray();
        for (Pin pin : PINS.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("key", pin.key());
            o.addProperty("label", pin.label());
            o.addProperty("x", pin.x());
            o.addProperty("y", pin.y());
            o.addProperty("z", pin.z());
            o.addProperty("icon", pin.icon());
            o.addProperty("argb", pin.argb());
            array.add(o);
        }
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("pins.json"), GSON.toJson(array));
        } catch (IOException e) {
            Siftec.LOGGER.warn("SIFTEC: could not save the map pins", e);
        }
    }

    // ------------------------------------------------------------------------------------------------ settings

    private static Path settingsFile() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("siftec").resolve("map").resolve("settings.json");
    }

    private static void loadSettings() {
        Path file = settingsFile();
        if (!Files.exists(file)) return;
        try {
            JsonObject o = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (o.has("minimap")) minimapOn = o.get("minimap").getAsBoolean();
            if (o.has("hidden")) for (JsonElement e : o.getAsJsonArray("hidden")) HIDDEN.add(e.getAsString());
        } catch (IOException | RuntimeException e) {
            Siftec.LOGGER.warn("SIFTEC: unreadable map file {}", file, e);
        }
    }

    public static void saveSettings() {
        JsonObject o = new JsonObject();
        o.addProperty("minimap", minimapOn);
        JsonArray hidden = new JsonArray();
        for (String h : HIDDEN) hidden.add(h);
        o.add("hidden", hidden);
        try {
            Path file = settingsFile();
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(o));
        } catch (IOException e) {
            Siftec.LOGGER.warn("SIFTEC: could not save the map settings", e);
        }
    }

    // ------------------------------------------------------------------------------------------------ ticking

    private static void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        if (mc.gui.screen() == null) {
            while (OPEN.consumeClick()) open();
            while (MINIMAP.consumeClick()) {
                minimapOn = !minimapOn;
                saveSettings();
            }
        }
        if (++ticks % REQUEST_TICKS != 0 || !unlocked() || !mappedHere() || !ClientPlayNetworking.canSend(MapPayloads.TileRequest.TYPE)) return;
        // ask for the tiles round the player and on the open map, each again at most every so often
        LongArrayList regions = new LongArrayList();
        LongArrayList versions = new LongArrayList();
        int px = mc.player.getBlockX(), pz = mc.player.getBlockZ();
        long here = MapGrid.regionOfBlock(px, pz);
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                long region = MapGrid.key(MapGrid.keyX(here) + dx, MapGrid.keyZ(here) + dz);
                want(region, dx == 0 && dz == 0 ? HERE_EVERY_MS : AROUND_EVERY_MS, regions, versions);
            }
        }
        if (mc.gui.screen() instanceof MapScreen screen) for (long region : screen.visibleRegions()) want(region, SCREEN_EVERY_MS, regions, versions);
        if (!regions.isEmpty()) ClientPlayNetworking.send(new MapPayloads.TileRequest(regions.toLongArray(), versions.toLongArray()));
    }

    private static void want(long region, long every, LongArrayList regions, LongArrayList versions) {
        if (regions.size() >= REGIONS_PER_REQUEST || regions.contains(region) || !FOG.containsKey(region)) return;
        // the disk copy is read first, so only a tile that changed since is sent again
        long version = ClientTiles.version(region);
        if (ClientTiles.loading(region) || ClientTiles.askedWithin(region, every)) return;
        regions.add(region);
        versions.add(version);
    }

    /** Opens the fullscreen map, if the company has it and the player is in the Overworld. */
    public static void open() {
        show(new MapScreen());
    }

    /** Shows this map screen, if the company has the map and the player is in the Overworld. */
    public static void show(MapScreen screen) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (!unlocked()) {
            mc.player.sendOverlayMessage(Component.translatable("siftec.map.locked"));
            return;
        }
        if (!mappedHere()) {
            mc.player.sendOverlayMessage(Component.translatable("siftec.map.surface_only"));
            return;
        }
        mc.gui.setScreen(screen);
    }

    /** Everything the map shows of the seed and the world in the regions given. */
    public static List<MapPoi> poisIn(Iterable<Long> regions) {
        List<MapPoi> out = new ArrayList<>();
        for (long region : regions) {
            List<MapPoi> list = POIS.get(region);
            if (list != null) out.addAll(list);
        }
        return out;
    }
}
