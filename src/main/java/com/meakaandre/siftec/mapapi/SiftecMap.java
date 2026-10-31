package com.meakaandre.siftec.mapapi;

import com.meakaandre.mapmod.api.FoundPoi;
import com.meakaandre.mapmod.api.GroupProvider;
import com.meakaandre.mapmod.api.MapFeature;
import com.meakaandre.mapmod.api.MapGlyphs;
import com.meakaandre.mapmod.api.MapModApi;
import com.meakaandre.mapmod.api.MapPoi;
import com.meakaandre.mapmod.api.MovingPoi;
import com.meakaandre.mapmod.api.PoiCategory;
import com.meakaandre.mapmod.api.PoiKind;
import com.meakaandre.mapmod.api.PoiSubType;
import com.meakaandre.mapmod.api.UnlockProvider;
import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.block.NodeBlock;
import com.meakaandre.siftec.building.ControllerBlockEntity;
import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.company.CompanyData;
import com.meakaandre.siftec.death.DeathCrates;
import com.meakaandre.siftec.drone.DronePortBlockEntity;
import com.meakaandre.siftec.hub.HubBlockEntity;
import com.meakaandre.siftec.hub.MapLock;
import com.meakaandre.siftec.node.NodeLook;
import com.meakaandre.siftec.node.NodeType;
import com.meakaandre.siftec.node.Purity;
import com.meakaandre.siftec.owner.Ownership;
import com.meakaandre.siftec.registry.ModBlocks;
import com.meakaandre.siftec.sim.SimVehicles;
import com.meakaandre.siftec.vehicle.VehicleKind;
import com.zurrtum.create.content.trains.station.StationBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.PotentSulfurBlockEntity;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * SIFTEC on MapMod's map (com.meakaandre.mapmod.api): a company is a map group, Field Research unlocks terrain, the
 * minimap and the fullscreen map (the compass is always on), and SIFTEC adds its own POIs: resource nodes and wells (with
 * purity, from the seed: {@link NodePois}), sulfur geysers and the company's key buildings (found in recorded chunks),
 * crash sites and artifacts (from the seed), and its vehicles and drones as moving POIs. Radar Tower sweeps reveal
 * through MapMod, and Death Crates keep their death markers there. Categories, kinds and their looks are SIFTEC's map's.
 */
public final class SiftecMap {
    public static PoiCategory NODES, WELLS, GEYSERS, CRASH_SITES, ARTIFACTS, BUILDINGS;
    public static PoiKind NODE, WELL, GEYSER, CRASH_SITE, MERCER_SPHERE, SOMERSLOOP, HUB, GATEWAY, RADAR_TOWER, TRAIN_STATION, TRUCK_STATION, DRONE_PORT;
    /** The moving kinds: one for each vehicle kind and one for drones, by the id SimVehicles shows them with. */
    public static final Map<String, PoiKind> MOVING = new HashMap<>();
    /** What finds geysers in a recorded chunk, and what finds the company's key buildings. */
    public static final Identifier GEYSER_SCANNER = Siftec.id("geysers"), BUILDING_SCANNER = Siftec.id("buildings"), NODE_SOURCE = Siftec.id("nodes");
    /** How far nodes and wells, and crash sites, show on the compass. */
    private static final int NODE_RANGE = 120, CRASH_RANGE = 300;
    /** Tints: as SIFTEC's map drew them. */
    private static final int GEYSER_TINT = 0xFFE6D94A, CRASH_TINT = 0xFFFF7A3D, SPHERE_TINT = 0xFFB98CFF, LOOP_TINT = 0xFFFF5FA8,
        HUB_TINT = 0xFFFFA530, BUILDING_TINT = 0xFFF0F0F0, OIL_TINT = 0xFF5A5148, WATER_TINT = 0xFFBFE6F2, VEHICLE_TINT = 0xFFFFC060,
        VEHICLE_DIM = 0xC0D8B070;

    private SiftecMap() {
    }

    private static Identifier id(String path) {
        return Siftec.id(path);
    }

    private static Component filter(String name) {
        return Component.translatable("siftec.map.filter." + name);
    }

    private static Component poi(String name) {
        return Component.translatable("siftec.map.poi." + name);
    }

    /** A node's or well's label, as the node block names it: its resource and purity. */
    private static Component nodeLabel(PoiKind kind, String sub, int purity) {
        NodeType type = NodeType.byId(sub);
        return type == null ? kind.label() : NodeBlock.label(type, Purity.values()[Math.clamp(purity, 0, 2)]);
    }

    /** The items a node of this type is drawn with, first choice first: each look's drop, clump and node block. */
    private static List<Identifier> nodeItems(NodeType type) {
        List<Identifier> out = new ArrayList<>();
        for (NodeLook look : NodeLook.all()) {
            if (look.type() != type) continue;
            for (String item : new String[]{look.drop(), look.clump(), look.node()}) if (!item.isEmpty()) out.add(Identifier.parse(item));
        }
        return out;
    }

    /** Registers SIFTEC with the map: the categories and kinds (both sides), and the providers and sources. */
    public static void register() {
        NODES = MapModApi.category(id("nodes"), filter("nodes"));
        WELLS = MapModApi.category(id("wells"), filter("wells"));
        GEYSERS = MapModApi.category(id("geysers"), filter("geysers"));
        CRASH_SITES = MapModApi.category(id("crash_sites"), filter("crash_sites"));
        ARTIFACTS = MapModApi.category(id("artifacts"), filter("artifacts"));
        BUILDINGS = MapModApi.category(id("buildings"), filter("buildings"));

        PoiKind.Builder node = MapModApi.kind(id("node"), NODES).label(poi("node")).labeller(SiftecMap::nodeLabel)
            .glyph(MapGlyphs.NODE, BUILDING_TINT).purity().subFilters().compassRange(NODE_RANGE);
        PoiKind.Builder well = MapModApi.kind(id("well"), WELLS).label(poi("well")).labeller(SiftecMap::nodeLabel)
            .glyph(MapGlyphs.DROP, WATER_TINT).purity().subFilters().compassRange(NODE_RANGE);
        for (NodeType t : NodeType.values()) {
            if (t.dimension() != Level.OVERWORLD) continue;
            Component label = Component.translatable(t.key());
            if (t.isFluid()) well.sub(new PoiSubType(t.id(), label, List.<Identifier>of(), -1, "oil".equals(t.id()) ? OIL_TINT : WATER_TINT));
            else node.sub(new PoiSubType(t.id(), label, nodeItems(t), -1, 0));
        }
        NODE = node.register();
        WELL = well.register();
        GEYSER = MapModApi.kind(id("geyser"), GEYSERS).label(poi("geyser")).glyph(MapGlyphs.GEYSER, GEYSER_TINT).filter(false).register();
        CRASH_SITE = MapModApi.kind(id("crash_site"), CRASH_SITES).label(poi("crash_site")).glyph(MapGlyphs.POD, CRASH_TINT).filter(false)
            .compassRange(CRASH_RANGE).register();
        MERCER_SPHERE = MapModApi.kind(id("mercer_sphere"), ARTIFACTS).label(poi("mercer_sphere")).glyph(MapGlyphs.SPHERE, SPHERE_TINT).register();
        SOMERSLOOP = MapModApi.kind(id("somersloop"), ARTIFACTS).label(poi("somersloop")).glyph(MapGlyphs.LOOP, LOOP_TINT).register();
        HUB = MapModApi.kind(id("hub"), BUILDINGS).label(poi("hub")).glyph(MapGlyphs.HUB, HUB_TINT).compassNearest().register();
        GATEWAY = MapModApi.kind(id("gateway"), BUILDINGS).label(poi("gateway")).glyph(MapGlyphs.GATE, HUB_TINT).register();
        RADAR_TOWER = MapModApi.kind(id("radar_tower"), BUILDINGS).label(poi("radar_tower")).glyph(MapGlyphs.RADAR, BUILDING_TINT).register();
        TRAIN_STATION = MapModApi.kind(id("train_station"), BUILDINGS).label(poi("train_station")).glyph(MapGlyphs.TRAIN, BUILDING_TINT).register();
        TRUCK_STATION = MapModApi.kind(id("truck_station"), BUILDINGS).label(poi("truck_station")).glyph(MapGlyphs.TRUCK, BUILDING_TINT).register();
        DRONE_PORT = MapModApi.kind(id("drone_port"), BUILDINGS).label(poi("drone_port")).glyph(MapGlyphs.DRONE, BUILDING_TINT).register();
        for (VehicleKind v : VehicleKind.values()) {
            MOVING.put(v.id, MapModApi.kind(id("vehicle_" + v.id), null).label(Component.translatable("entity.siftec." + v.id))
                .glyph(MapGlyphs.TRUCK, VEHICLE_TINT).dimColour(VEHICLE_DIM).register());
        }
        MOVING.put("drone", MapModApi.kind(id("drone"), null).label(Component.translatable("siftec.map.drone"))
            .glyph(MapGlyphs.DRONE, VEHICLE_TINT).dimColour(VEHICLE_DIM).register());

        MapModApi.setGroups(GROUPS);
        MapModApi.setUnlocks(UNLOCKS);
        MapModApi.addSource(NODE_SOURCE, NodePois.SOURCE);
        MapModApi.addChunkScanner(GEYSER_SCANNER, (level, chunk) -> {
            List<FoundPoi> out = new ArrayList<>();
            for (BlockEntity be : chunk.getBlockEntities().values()) {
                if (be instanceof PotentSulfurBlockEntity) out.add(FoundPoi.everyone(at(GEYSER, be.getBlockPos())));
            }
            return out;
        });
        MapModApi.addChunkScanner(BUILDING_SCANNER, (level, chunk) -> {
            List<FoundPoi> out = new ArrayList<>();
            for (BlockEntity be : chunk.getBlockEntities().values()) {
                FoundPoi f = building(be);
                if (f != null) out.add(f);
            }
            return out;
        });
        MapModApi.addMovingSource(SiftecMap::moving);
        // a death that left a Death Crate already has its marker, kept on the crate
        MapModApi.skipDeathMarkers(DeathCrates::placedJustNow);
    }

    private static MapPoi at(PoiKind kind, BlockPos p) {
        return MapPoi.of(kind, p.getX(), p.getY(), p.getZ());
    }

    /**
     * A key building, seen by its company only; an unowned Train Station by everyone. A building of no company shows to
     * nobody.
     */
    private static @Nullable FoundPoi building(BlockEntity be) {
        PoiKind kind = null;
        String owner = "";
        if (be instanceof HubBlockEntity hub) {
            if (!hub.prefab) return null;
            kind = be.getBlockState().is(ModBlocks.WORMHOLE_GATEWAY.get()) ? GATEWAY : HUB;
            owner = hub.companyId;
        } else if (be instanceof ControllerBlockEntity c) {
            if (c.buildingId.equals("radar_tower")) kind = RADAR_TOWER;
            else if (c.buildingId.equals("truck_station")) kind = TRUCK_STATION;
            owner = c.companyId;
        } else if (be instanceof DronePortBlockEntity port) {
            kind = DRONE_PORT;
            owner = port.companyId;
        } else if (be instanceof StationBlockEntity) {
            String id = be.getAttached(Ownership.OWNER);
            return new FoundPoi(at(TRAIN_STATION, be.getBlockPos()), id == null ? "" : id);
        }
        if (kind == null || owner == null || owner.isEmpty()) return null;
        return new FoundPoi(at(kind, be.getBlockPos()), owner);
    }

    // ------------------------------------------------------------------------------------------------ geysers

    /** Geysers a search found in a chunk nobody has loaded: they replace that chunk's geysers on the map. */
    public static void geysersFound(ServerLevel level, int chunkX, int chunkZ, List<BlockPos> geysers) {
        List<FoundPoi> list = new ArrayList<>();
        for (BlockPos p : geysers) list.add(FoundPoi.everyone(at(GEYSER, p)));
        MapModApi.setChunkPois(level, GEYSER_SCANNER, chunkX, chunkZ, list);
    }

    /** Every geyser on the map within this many blocks of a spot, nearest first. */
    public static List<BlockPos> geysersNear(int x, int z, int blocks) {
        List<BlockPos> out = new ArrayList<>();
        for (MapPoi p : MapModApi.foundNear(GEYSER, x, z, blocks)) out.add(new BlockPos(p.x(), p.y(), p.z()));
        return out;
    }

    // ------------------------------------------------------------------------------------------------ groups and unlocks

    /** A company is a map group: its members share fog, company markers and the compass. */
    public static final GroupProvider GROUPS = new GroupProvider() {
        @Override
        public String groupOf(MinecraftServer server, UUID player) {
            Company company = CompanyData.get(server).ofMember(player.toString());
            return company == null ? "" : company.id;
        }

        @Override
        public boolean same(String a, String b) {
            return Companies.same(a, b);
        }

        @Override
        public boolean ignored(ServerPlayer player) {
            return Companies.isFake(player);
        }

        @Override
        public String sharedLabel() {
            return "siftec.map.company";
        }
    };

    /** Field Research gives the Map: terrain, the minimap and the fullscreen map together. */
    public static final UnlockProvider UNLOCKS = new UnlockProvider() {
        @Override
        public boolean unlocked(ServerPlayer player, MapFeature feature) {
            return MapLock.open(Companies.find(player));
        }

        @Override
        public String lockedMessage(ServerPlayer player) {
            return "siftec.map.locked";
        }
    };

    // ------------------------------------------------------------------------------------------------ vehicles

    private static List<SimVehicles.Shown> shown = List.of();
    private static int shownAt = -1;

    /** The player's company's vehicles and drones in their dimension, where they are or (driven by time) would be. */
    private static List<MovingPoi> moving(ServerPlayer player, String group) {
        MinecraftServer server = player.level().getServer();
        // worked out once a tick for everyone, not once for each player
        if (shownAt != server.getTickCount()) {
            shown = SimVehicles.positions(server);
            shownAt = server.getTickCount();
        }
        if (group.isEmpty()) return List.of();
        String dim = player.level().dimension().identifier().toString();
        List<MovingPoi> out = new ArrayList<>();
        for (SimVehicles.Shown s : shown) {
            if (!s.dim().equals(dim) || !Companies.same(s.company(), group)) continue;
            PoiKind kind = MOVING.get(s.kind().toLowerCase(Locale.ROOT));
            if (kind == null) continue;
            out.add(MovingPoi.of(kind, s.x(), s.z(), s.yaw(), s.simulated(), s.simulated() ? "siftec.map.by_time" : ""));
        }
        return out;
    }
}
