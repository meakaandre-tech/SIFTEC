package com.meakaandre.siftec.map;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.collect.Collectible;
import com.meakaandre.siftec.collect.Collectibles;
import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.company.CompanyData;
import com.meakaandre.siftec.death.DeathCrates;
import com.meakaandre.siftec.node.Node;
import com.meakaandre.siftec.node.NodeMap;
import com.meakaandre.siftec.node.NodeType;
import com.meakaandre.siftec.sim.SimVehicles;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gamerules.GameRules;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The map's server side (the Map tab of the design): fog of war shared by each company, the Overworld's terrain
 * tiles, what the map shows (nodes, wells, geysers, crash sites, artifacts, the company's key buildings), markers
 * and death markers, and the company's players for the compass. It sends each client only what its company has
 * explored, and terrain only once Field Research has given the company the Map.
 */
public final class MapServer {
    /** The token Field Research gives (tools/tables/content.py): the minimap and the fullscreen map. */
    public static final String TOKEN = "map";
    /** Chunks round a company member that count as visited. */
    public static final int VISIT_RADIUS = 6;
    /** Regions of POIs or fog sent in one packet at most. */
    private static final int BATCH = 64;
    /** Tiles sent for one request at most. */
    private static final int TILES_PER_REQUEST = 12;
    /** Chunks a Radar Tower's sweep guesses in one go on the worker thread. */
    private static final int RADAR_BATCH = 48;
    /** A player's tile requests are answered at most this often (ticks); the client asks once a second. */
    private static final int TILE_REQUEST_TICKS = 10;
    /** Regions of a tile request looked at, at most. */
    private static final int REQUEST_REGIONS = 64;
    /** Regions of seed POIs kept per company; past this the cache is emptied and worked out again as needed. */
    private static final int SEED_REGIONS_KEPT = 4096;
    /** Ticks in a second: fog clearing round players, found-change checks and mover updates run once a second. */
    private static final int TICKS_PER_SECOND = 20;
    /** Ticks between company-mate updates (twice a second). */
    private static final int MATES_TICKS = 10;
    /** Ticks between fog saves (a minute). */
    private static final int FOG_SAVE_TICKS = 1200;
    /** Packed tiles cached at most; past this the cache is emptied. */
    private static final int PACKED_KEPT = 256;
    /** Multiplier mixing the done-count hash into a company's seed stamp. */
    private static final int STAMP_HASH = 31;

    private static @Nullable String mapId;
    /** Per company: what the seed puts in each explored region (nodes, wells, crash sites, artifacts). */
    private static final Map<String, Long2ObjectOpenHashMap<List<MapPoi>>> SEED = new HashMap<>();
    /** Per company: what its seed POIs were worked out for (finished milestones and collected things). */
    private static final Map<String, Integer> SEED_STAMP = new HashMap<>();
    /** Per company: regions waiting for their seed POIs. */
    private static final Map<String, LongOpenHashSet> PENDING = new HashMap<>();
    private static final Set<String> RUNNING = new HashSet<>();
    /** Which company each online player was last sent the map of. */
    private static final Map<UUID, String> SYNCED = new HashMap<>();
    /** When each player's last tile request was answered (server ticks). */
    private static final Map<UUID, Integer> TILE_ASKED = new HashMap<>();
    /** Players last sent an empty list of company players: an empty list is sent once, not every half second. */
    private static final Set<UUID> NO_MATES = new HashSet<>();
    /** Packed tiles, kept while their version is current. */
    private static final Long2ObjectOpenHashMap<byte[]> PACKED = new Long2ObjectOpenHashMap<>();
    private static final Long2LongOpenHashMap PACKED_AT = new Long2LongOpenHashMap();

    private MapServer() {
    }

    /** Only the Overworld's surface is mapped. */
    public static boolean mapped(Level level) {
        return level.dimension() == Level.OVERWORLD;
    }

    public static boolean unlocked(@Nullable Company company) {
        return company != null && company.hasToken(TOKEN);
    }

    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(MapPayloads.Hello.TYPE, MapPayloads.Hello.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(MapPayloads.Fog.TYPE, MapPayloads.Fog.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(MapPayloads.FogDelta.TYPE, MapPayloads.FogDelta.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(MapPayloads.Pois.TYPE, MapPayloads.Pois.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(MapPayloads.Tile.TYPE, MapPayloads.Tile.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(MapPayloads.Markers.TYPE, MapPayloads.Markers.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(MapPayloads.Mates.TYPE, MapPayloads.Mates.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(MapPayloads.Movers.TYPE, MapPayloads.Movers.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(MapPayloads.TileRequest.TYPE, MapPayloads.TileRequest.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(MapPayloads.MarkerEdit.TYPE, MapPayloads.MarkerEdit.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(MapPayloads.TileRequest.TYPE, (payload, context) -> context.server().execute(() -> tiles(context.player(), payload)));
        ServerPlayNetworking.registerGlobalReceiver(MapPayloads.MarkerEdit.TYPE, (payload, context) -> context.server().execute(() -> edit(context.player(), payload)));

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            MapTerrain.start(server);
            mapId = readMapId();
            // company players show on the compass; vanilla's locator bar would show everyone, so it is switched off
            server.getGameRules().set(GameRules.LOCATOR_BAR, false, server);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            MapFog.stop();
            MapTerrain.stop();
            SEED.clear();
            SEED_STAMP.clear();
            PENDING.clear();
            RUNNING.clear();
            SYNCED.clear();
            TILE_ASKED.clear();
            NO_MATES.clear();
            PACKED.clear();
            PACKED_AT.clear();
            mapId = null;
        });
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> MapTerrain.loaded(level, chunk));
        ServerPlayerEvents.JOIN.register(MapServer::sync);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID id = handler.getPlayer().getUUID();
            SYNCED.remove(id);
            TILE_ASKED.remove(id);
            NO_MATES.remove(id);
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            // a death that left a Death Crate already has its marker, on the crate
            if (entity instanceof ServerPlayer player && !Companies.isFake(player) && !DeathCrates.placedJustNow(player)) deathMarker(player);
        });
        ServerTickEvents.END_SERVER_TICK.register(MapServer::tick);
    }

    private static String readMapId() {
        Path root = MapTerrain.root();
        if (root == null) return UUID.randomUUID().toString();
        Path file = root.resolve("id.txt");
        try {
            if (Files.exists(file)) {
                String id = Files.readString(file).strip();
                if (id.matches("[0-9a-f-]{36}")) return id;
            }
            String id = UUID.randomUUID().toString();
            Files.writeString(file, id);
            return id;
        } catch (IOException e) {
            Siftec.LOGGER.error("SIFTEC: could not read or write the map file {}", file, e);
            return UUID.randomUUID().toString();
        }
    }

    // ------------------------------------------------------------------------------------------------ ticking

    private static void tick(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        MapTerrain.tick(overworld);
        int t = server.getTickCount();
        if (t % TICKS_PER_SECOND == 0) explore(server);
        if (t % MATES_TICKS == 5) mates(server);
        if (t % TICKS_PER_SECOND == 15) movers(server);
        if (t % TICKS_PER_SECOND == 10) {
            long[] changed = MapTerrain.takeFoundChanges();
            if (changed.length > 0) foundChanged(server, changed);
            runPending(server);
        }
        if (t % FOG_SAVE_TICKS == 600) MapFog.saveAll();
    }

    /** Company members clear the fog round them. */
    private static void explore(MinecraftServer server) {
        Map<String, LongArrayList> cleared = new HashMap<>();
        Map<String, LongOpenHashSet> fresh = new HashMap<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!mapped(player.level()) || Companies.isFake(player)) continue;
            Company company = Companies.find(player);
            if (company == null) continue;
            LongOpenHashSet newRegions = fresh.computeIfAbsent(company.id, k -> new LongOpenHashSet());
            LongArrayList list = MapFog.reveal(company.id, player.blockPosition().getX() >> 4, player.blockPosition().getZ() >> 4, VISIT_RADIUS, newRegions);
            if (!list.isEmpty()) cleared.computeIfAbsent(company.id, k -> new LongArrayList()).addAll(list);
        }
        CompanyData data = CompanyData.get(server);
        cleared.forEach((id, chunks) -> {
            Company company = data.byId(id);
            if (company == null) return;
            send(server, company, new MapPayloads.FogDelta(chunks.toLongArray()));
            uncovered(server, company, chunks);
        });
        fresh.forEach((id, regions) -> {
            if (!regions.isEmpty()) PENDING.computeIfAbsent(id, k -> new LongOpenHashSet()).addAll(regions);
        });
    }

    private static void mates(MinecraftServer server) {
        CompanyData data = CompanyData.get(server);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (Companies.isFake(player)) continue;
            Company company = data.ofMember(player.getUUID().toString());
            List<MapPayloads.Mate> mates = new ArrayList<>();
            if (company != null) {
                for (String member : company.members) {
                    if (member.equals(player.getUUID().toString())) continue;
                    ServerPlayer other = server.getPlayerList().getPlayer(UUID.fromString(member));
                    if (other == null || other.level() != player.level() || other.isSpectator()) continue;
                    mates.add(new MapPayloads.Mate(other.getGameProfile().name(), other.getX(), other.getY(), other.getZ(), other.getYRot()));
                }
            }
            // nobody to show: one empty list clears the client's, then nothing until someone turns up
            if (mates.isEmpty() && !NO_MATES.add(player.getUUID())) continue;
            if (!mates.isEmpty()) NO_MATES.remove(player.getUUID());
            send(player, new MapPayloads.Mates(mates));
        }
    }

    /** Each player's company's vehicles and drones in their dimension, where they are or (driven by time) would be. */
    private static void movers(MinecraftServer server) {
        List<SimVehicles.Shown> all = SimVehicles.positions(server);
        CompanyData data = CompanyData.get(server);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (Companies.isFake(player)) continue;
            Company company = data.ofMember(player.getUUID().toString());
            String dim = player.level().dimension().identifier().toString();
            List<MapPayloads.Mover> movers = new ArrayList<>();
            for (var s : all) {
                if (!s.dim().equals(dim) || company == null || !Companies.same(s.company(), company.id)) continue;
                movers.add(new MapPayloads.Mover(s.kind(), s.x(), s.z(), s.yaw(), s.simulated()));
            }
            if (movers.isEmpty() && !NO_MOVERS.add(player.getUUID())) continue;
            if (!movers.isEmpty()) NO_MOVERS.remove(player.getUUID());
            send(player, new MapPayloads.Movers(movers));
        }
    }

    private static final Set<UUID> NO_MOVERS = new HashSet<>();

    // ------------------------------------------------------------------------------------------------ sending

    private static void send(ServerPlayer player, CustomPacketPayload payload) {
        if (!Companies.isFake(player) && ServerPlayNetworking.canSend(player, payload.type())) ServerPlayNetworking.send(player, payload);
    }

    /** To every member of the company who is online. */
    public static void send(MinecraftServer server, Company company, CustomPacketPayload payload) {
        for (String member : company.members) {
            try {
                ServerPlayer player = server.getPlayerList().getPlayer(UUID.fromString(member));
                if (player != null) send(player, payload);
            } catch (IllegalArgumentException ignored) {
                // a test company's made-up member
                Siftec.LOGGER.debug("SIFTEC: map member {} is not a UUID; not sent to", member, ignored);
            }
        }
    }

    /** Everything a player's client needs: the map's id, their company's fog, their markers and what the map shows. */
    public static void sync(ServerPlayer player) {
        if (Companies.isFake(player)) return;
        MinecraftServer server = player.level().getServer();
        Company company = Companies.find(player);
        SYNCED.put(player.getUUID(), company == null ? "" : company.id);
        if (mapId != null) send(player, new MapPayloads.Hello(mapId));
        sendMarkers(player);
        if (company == null) {
            send(player, new MapPayloads.Fog(true, List.of()));
            return;
        }
        Long2ObjectOpenHashMap<long[]> fog = MapFog.of(company.id);
        List<MapPayloads.FogRegion> regions = new ArrayList<>();
        boolean first = true;
        for (var e : fog.long2ObjectEntrySet()) {
            regions.add(new MapPayloads.FogRegion(e.getLongKey(), e.getValue().clone()));
            if (regions.size() >= BATCH) {
                send(player, new MapPayloads.Fog(first, regions));
                regions = new ArrayList<>();
                first = false;
            }
        }
        send(player, new MapPayloads.Fog(first, regions));
        // what the map shows: from the cache where it is current, worked out again where not
        if (stamp(company) != SEED_STAMP.getOrDefault(company.id, -1)) {
            refreshSeed(server, company);
        } else {
            Long2ObjectOpenHashMap<List<MapPoi>> seed = SEED.get(company.id);
            List<MapPayloads.RegionPois> out = new ArrayList<>();
            for (long region : fog.keySet()) {
                List<MapPoi> s = seed == null ? null : seed.get(region);
                if (s == null) {
                    PENDING.computeIfAbsent(company.id, k -> new LongOpenHashSet()).add(region);
                    continue;
                }
                out.add(new MapPayloads.RegionPois(region, merged(s, region, company.id)));
                if (out.size() >= BATCH) {
                    send(player, new MapPayloads.Pois(out));
                    out = new ArrayList<>();
                }
            }
            if (!out.isEmpty()) send(player, new MapPayloads.Pois(out));
        }
    }

    /**
     * Called whenever a player's company state is sent (CompanyState): a player who changed company gets the new
     * company's map; a company whose unlocks or collected things changed gets its POIs worked out again.
     */
    public static void companyChanged(ServerPlayer player, @Nullable Company company) {
        if (Companies.isFake(player)) return;
        String id = company == null ? "" : company.id;
        String before = SYNCED.get(player.getUUID());
        if (before == null) return; // not joined yet: the join sends everything
        if (!before.equals(id)) {
            sync(player);
            return;
        }
        if (company != null && stamp(company) != SEED_STAMP.getOrDefault(company.id, -1) && !RUNNING.contains(company.id)) {
            refreshSeed(player.level().getServer(), company);
        }
    }

    private static int stamp(Company company) {
        return company.done.hashCode() * STAMP_HASH + company.collected.size();
    }

    // ------------------------------------------------------------------------------------------------ points of interest

    /** What a region shows the company: its seed POIs and what was found there, in the chunks it has explored only. */
    private static List<MapPoi> merged(List<MapPoi> seed, long region, String companyId) {
        List<MapPoi> out = new ArrayList<>();
        for (MapPoi p : seed) if (MapFog.explored(companyId, p.x() >> 4, p.z() >> 4)) out.add(p);
        for (MapPoi p : MapTerrain.foundFor(region, companyId)) if (MapFog.explored(companyId, p.x() >> 4, p.z() >> 4)) out.add(p);
        return out;
    }

    /**
     * Chunks the company just explored: the regions they are in are sent again if a POI stands in one of them (POIs are
     * only sent for explored chunks). Regions whose POIs are not worked out yet are left to the pending work.
     */
    private static void uncovered(MinecraftServer server, Company company, LongList chunks) {
        Long2ObjectOpenHashMap<List<MapPoi>> seed = SEED.get(company.id);
        if (seed == null) return;
        LongOpenHashSet fresh = new LongOpenHashSet(chunks);
        LongOpenHashSet regions = new LongOpenHashSet();
        for (long c : chunks) regions.add(MapGrid.regionOfChunk(MapGrid.keyX(c), MapGrid.keyZ(c)));
        List<MapPayloads.RegionPois> out = new ArrayList<>();
        for (long region : regions) {
            List<MapPoi> s = seed.get(region);
            if (s == null) continue;
            boolean shows = false;
            for (MapPoi p : s) if (fresh.contains(MapGrid.key(p.x() >> 4, p.z() >> 4))) shows = true;
            if (!shows) for (MapPoi p : MapTerrain.foundFor(region, company.id)) if (fresh.contains(MapGrid.key(p.x() >> 4, p.z() >> 4))) shows = true;
            if (!shows) continue;
            out.add(new MapPayloads.RegionPois(region, merged(s, region, company.id)));
            if (out.size() >= BATCH) {
                send(server, company, new MapPayloads.Pois(out));
                out = new ArrayList<>();
            }
        }
        if (!out.isEmpty()) send(server, company, new MapPayloads.Pois(out));
    }

    /** Works out the company's seed POIs again for every region it has explored. */
    private static void refreshSeed(MinecraftServer server, Company company) {
        SEED.remove(company.id);
        SEED_STAMP.put(company.id, stamp(company));
        PENDING.computeIfAbsent(company.id, k -> new LongOpenHashSet()).addAll(MapFog.of(company.id).keySet());
        runPending(server);
    }

    /** What a company is allowed to see of the seed's things, read on the server thread for the worker. */
    private record Knows(Set<NodeType> nodes, Set<Collectible> objects, Set<String> collected) {
        static Knows of(Company company) {
            Set<NodeType> nodes = EnumSet.noneOf(NodeType.class);
            for (NodeType type : NodeType.values()) if (type.dimension() == Level.OVERWORLD && type.onScanner(company)) nodes.add(type);
            Set<Collectible> objects = EnumSet.noneOf(Collectible.class);
            for (Collectible c : Collectible.values()) if (c.scannerToken.isEmpty() || company.hasToken(c.scannerToken)) objects.add(c);
            return new Knows(nodes, objects, Set.copyOf(company.collected));
        }
    }

    private static void runPending(MinecraftServer server) {
        if (PENDING.isEmpty()) return;
        ServerLevel level = server.overworld();
        if (!NodeMap.hasNodes(level) || NodeMap.origin() == null) return;
        CompanyData data = CompanyData.get(server);
        for (String id : new ArrayList<>(PENDING.keySet())) {
            if (RUNNING.contains(id)) continue;
            Company company = data.byId(id);
            LongOpenHashSet regions = PENDING.remove(id);
            if (company == null || regions == null || regions.isEmpty()) continue;
            Knows knows = Knows.of(company);
            int stamp = stamp(company);
            long[] list = regions.toLongArray();
            RUNNING.add(id);
            NodeMap.async(server, () -> {
                Long2ObjectOpenHashMap<List<MapPoi>> out = new Long2ObjectOpenHashMap<>();
                for (long region : list) out.put(region, seedPois(level, region, knows));
                return out;
            }, result -> {
                RUNNING.remove(id);
                if (result == null) return;
                if (stamp != stamp(company)) {
                    // the company changed while it ran: do it all again
                    refreshSeed(server, company);
                    return;
                }
                Long2ObjectOpenHashMap<List<MapPoi>> seed = SEED.computeIfAbsent(id, k -> new Long2ObjectOpenHashMap<>());
                // a company that has explored very far: forget what it has not looked at lately rather than keep it all
                if (seed.size() + result.size() > SEED_REGIONS_KEPT) seed.clear();
                List<MapPayloads.RegionPois> out = new ArrayList<>();
                for (var e : result.long2ObjectEntrySet()) {
                    seed.put(e.getLongKey(), e.getValue());
                    out.add(new MapPayloads.RegionPois(e.getLongKey(), merged(e.getValue(), e.getLongKey(), id)));
                    if (out.size() >= BATCH) {
                        send(server, company, new MapPayloads.Pois(out));
                        out = new ArrayList<>();
                    }
                }
                if (!out.isEmpty()) send(server, company, new MapPayloads.Pois(out));
            });
        }
    }

    /** For the selftest: a region's seed POIs as the company would get them, worked out here and now. */
    public static List<MapPoi> poisFor(ServerLevel level, Company company, long region) {
        return seedPois(level, region, Knows.of(company));
    }

    /** Off the server thread: the nodes, wells and collectibles the seed puts in a region, as far as the company knows. */
    private static List<MapPoi> seedPois(ServerLevel level, long region, Knows knows) {
        List<MapPoi> out = new ArrayList<>();
        int minX = MapGrid.keyX(region) * MapGrid.REGION_BLOCKS, minZ = MapGrid.keyZ(region) * MapGrid.REGION_BLOCKS;
        int maxX = minX + MapGrid.REGION_BLOCKS, maxZ = minZ + MapGrid.REGION_BLOCKS;
        for (int cx = Math.floorDiv(minX, NodeMap.CELL); cx <= Math.floorDiv(maxX - 1, NodeMap.CELL); cx++) {
            for (int cz = Math.floorDiv(minZ, NodeMap.CELL); cz <= Math.floorDiv(maxZ - 1, NodeMap.CELL); cz++) {
                for (Node node : NodeMap.inCell(level, cx, cz)) {
                    if (node.x() < minX || node.x() >= maxX || node.z() < minZ || node.z() >= maxZ || !knows.nodes.contains(node.type())) continue;
                    // a cave or Nether node with nowhere to stand was left out of the world: not on the map either
                    if (NodeMap.placeless(node)) continue;
                    int y = NodeMap.groundOf(node).map(g -> g + 1).orElse(level.getSeaLevel());
                    out.add(new MapPoi(node.type().isFluid() ? MapPoi.Kind.WELL : MapPoi.Kind.NODE, node.type().id(), node.purity().ordinal(), node.x(), y, node.z()));
                }
            }
        }
        for (int cx = Math.floorDiv(minX, Collectibles.CELL); cx <= Math.floorDiv(maxX - 1, Collectibles.CELL); cx++) {
            for (int cz = Math.floorDiv(minZ, Collectibles.CELL); cz <= Math.floorDiv(maxZ - 1, Collectibles.CELL); cz++) {
                Collectibles.Spot spot = Collectibles.inCell(level, cx, cz).orElse(null);
                if (spot == null || spot.x() < minX || spot.x() >= maxX || spot.z() < minZ || spot.z() >= maxZ) continue;
                if (!knows.objects.contains(spot.type()) || knows.collected.contains(spot.key())) continue;
                MapPoi.Kind kind = switch (spot.type()) {
                    case CRASH_SITE -> MapPoi.Kind.CRASH_SITE;
                    case MERCER_SPHERE -> MapPoi.Kind.MERCER_SPHERE;
                    case SOMERSLOOP -> MapPoi.Kind.SOMERSLOOP;
                };
                out.add(new MapPoi(kind, "", 0, spot.x(), Collectibles.y(level, spot, level.getSeaLevel()), spot.z()));
            }
        }
        return out;
    }

    /** Geysers or key buildings appeared or went: the companies that have those regions explored get them again. */
    private static void foundChanged(MinecraftServer server, long[] regions) {
        for (Company company : CompanyData.get(server).companies().values()) {
            Long2ObjectOpenHashMap<long[]> fog = MapFog.of(company.id);
            Long2ObjectOpenHashMap<List<MapPoi>> seed = SEED.get(company.id);
            List<MapPayloads.RegionPois> out = new ArrayList<>();
            for (long region : regions) {
                if (!fog.containsKey(region)) continue;
                List<MapPoi> s = seed == null ? null : seed.get(region);
                if (s == null) {
                    PENDING.computeIfAbsent(company.id, k -> new LongOpenHashSet()).add(region);
                    continue;
                }
                out.add(new MapPayloads.RegionPois(region, merged(s, region, company.id)));
            }
            if (!out.isEmpty()) send(server, company, new MapPayloads.Pois(out));
        }
    }

    // ------------------------------------------------------------------------------------------------ terrain tiles

    private static void tiles(ServerPlayer player, MapPayloads.TileRequest request) {
        Company company = Companies.find(player);
        if (!unlocked(company) || request.regions().length != request.versions().length) return;
        // at most one request answered every so often per player, and only so many of its regions looked at
        int now = player.level().getServer().getTickCount();
        Integer last = TILE_ASKED.get(player.getUUID());
        if (last != null && now - last < TILE_REQUEST_TICKS && now >= last) return;
        TILE_ASKED.put(player.getUUID(), now);
        Long2ObjectOpenHashMap<long[]> fog = MapFog.of(company.id);
        int sent = 0;
        int looked = Math.min(request.regions().length, REQUEST_REGIONS);
        for (int i = 0; i < looked && sent < TILES_PER_REQUEST; i++) {
            long region = request.regions()[i];
            if (!fog.containsKey(region)) continue;
            MapTile tile = MapTerrain.tile(region);
            if (tile == null || tile.version == 0 || tile.version <= request.versions()[i]) continue;
            byte[] packed = PACKED.get(region);
            if (packed == null || PACKED_AT.get(region) != tile.version) {
                packed = tile.pack();
                PACKED.put(region, packed);
                PACKED_AT.put(region, tile.version);
                if (PACKED.size() > PACKED_KEPT) {
                    PACKED.clear();
                    PACKED_AT.clear();
                }
            }
            send(player, new MapPayloads.Tile(region, packed));
            sent++;
        }
    }

    // ------------------------------------------------------------------------------------------------ Radar Towers

    /**
     * A powered Radar Tower's sweep: clears the company's fog over a disc round the tower and fills land nobody has
     * loaded there with the world generator's guess, so it shows on the map at once.
     */
    public static void radarSweep(ServerLevel level, Company company, BlockPos at, int radiusBlocks) {
        if (!mapped(level) || company.id.isEmpty()) return;
        MinecraftServer server = level.getServer();
        int radius = Math.max(1, radiusBlocks >> 4);
        LongOpenHashSet newRegions = new LongOpenHashSet();
        int ccx = at.getX() >> 4, ccz = at.getZ() >> 4;
        LongArrayList cleared = MapFog.reveal(company.id, ccx, ccz, radius, newRegions);
        if (!cleared.isEmpty()) {
            uncovered(server, company, cleared);
            // the touched regions' whole fog: far fewer bytes than the chunks one by one
            LongOpenHashSet touched = new LongOpenHashSet();
            for (long c : cleared) touched.add(MapGrid.regionOfChunk(MapGrid.keyX(c), MapGrid.keyZ(c)));
            Long2ObjectOpenHashMap<long[]> fog = MapFog.of(company.id);
            List<MapPayloads.FogRegion> regions = new ArrayList<>();
            for (long r : touched) regions.add(new MapPayloads.FogRegion(r, fog.get(r).clone()));
            for (int i = 0; i < regions.size(); i += BATCH) {
                send(server, company, new MapPayloads.Fog(false, new ArrayList<>(regions.subList(i, Math.min(regions.size(), i + BATCH)))));
            }
        }
        if (!newRegions.isEmpty()) PENDING.computeIfAbsent(company.id, k -> new LongOpenHashSet()).addAll(newRegions);
        // guesses for every chunk in the disc with nothing recorded
        LongArrayList wanted = new LongArrayList();
        int r2 = radius * radius;
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                if (dx * dx + dz * dz > r2) continue;
                if (MapTerrain.needsGuess(ccx + dx, ccz + dz)) wanted.add(MapGrid.key(ccx + dx, ccz + dz));
            }
        }
        if (wanted.isEmpty()) return;
        int sea = level.getSeaLevel();
        // thousands of chunks: guessed a small batch at a time, so the shared worker is never held up for long
        NodeMap.batched(server, wanted, RADAR_BATCH, batch -> {
            List<MapTerrain.Guess> guesses = new ArrayList<>(batch.size());
            for (long c : batch) guesses.add(MapTerrain.guess(level, MapGrid.keyX(c), MapGrid.keyZ(c), sea));
            return guesses;
        }, guesses -> {
            if (guesses != null) MapTerrain.apply(guesses);
        }, () -> {
        });
    }

    // ------------------------------------------------------------------------------------------------ markers

    private static void sendMarkers(ServerPlayer player) {
        String companyId = Companies.idOf(player);
        MapMarkers markers = MapMarkers.get(player.level().getServer());
        send(player, new MapPayloads.Markers(markers.visibleTo(player.getUUID().toString(), companyId)));
    }

    /** Sends the markers again to everyone who sees this one. */
    private static void markersChanged(MinecraftServer server, MapMarker m) {
        Set<String> who = new HashSet<>();
        who.add(m.owner);
        if (m.company) {
            Company company = Companies.byId(server, m.companyId);
            if (company != null) who.addAll(company.members);
        }
        for (String id : who) {
            try {
                ServerPlayer player = server.getPlayerList().getPlayer(UUID.fromString(id));
                if (player != null) sendMarkers(player);
            } catch (IllegalArgumentException ignored) {
                // a test company's made-up member
                Siftec.LOGGER.debug("SIFTEC: map member {} is not a UUID; not sent to", id, ignored);
            }
        }
    }

    /** True if the player may change or remove the marker: their own, or a company marker of their company. */
    public static boolean mayEdit(MapMarker m, String player, String companyId) {
        return m.owner.equals(player) || (m.company && !m.death && Companies.same(m.companyId, companyId));
    }

    private static void edit(ServerPlayer player, MapPayloads.MarkerEdit payload) {
        String result = apply(player.level().getServer(), player.getUUID().toString(), Companies.idOf(player), payload.op(), payload.marker());
        if (result != null) player.sendOverlayMessage(Component.translatable(result));
    }

    /**
     * Places, changes or removes a marker for a player of a company, as their client asked. Returns null when done,
     * or the message key saying why not. Also used by the selftest, which plays a second company member.
     */
    public static @Nullable String apply(MinecraftServer server, String player, String companyId, int op, MapMarker in) {
        MapMarkers markers = MapMarkers.get(server);
        in.clamp();
        if (op == MapPayloads.ADD) {
            if (markers.countOf(player) >= MapMarkers.MAX_PER_PLAYER) return "siftec.map.too_many";
            MapMarker m = new MapMarker();
            m.id = UUID.randomUUID().toString();
            m.dimension = Level.OVERWORLD.identifier().toString();
            m.x = in.x;
            m.y = in.y;
            m.z = in.z;
            m.name = in.name;
            m.icon = in.icon;
            m.colour = in.colour;
            m.company = in.company && !companyId.isEmpty();
            m.owner = player;
            m.companyId = companyId;
            m.at = System.currentTimeMillis();
            markers.all().add(m);
            markers.setDirty();
            markersChanged(server, m);
            return null;
        }
        MapMarker m = markers.byId(in.id);
        if (m == null) return "siftec.map.no_marker";
        if (!mayEdit(m, player, companyId)) return "siftec.map.not_yours";
        if (op == MapPayloads.DELETE) {
            markers.all().remove(m);
            markers.setDirty();
            markersChanged(server, m);
            return null;
        }
        // an update: a marker going private or shared is sent to the old and the new viewers alike
        MapMarker before = new MapMarker();
        before.owner = m.owner;
        before.company = m.company;
        before.companyId = m.companyId;
        m.name = in.name;
        m.icon = in.icon;
        m.colour = in.colour;
        if (!m.death) {
            m.company = in.company && !companyId.isEmpty();
            if (m.company) m.companyId = companyId;
        }
        markers.setDirty();
        markersChanged(server, before);
        markersChanged(server, m);
        return null;
    }

    /** A death marker where the player died (or stands, for the tests); only the last few are kept. */
    public static void deathMarker(ServerPlayer player) {
        deathMarker(player, player.level(), player.blockPosition(), false);
    }

    /**
     * A death marker at a player's Death Crate: kept, whatever the number of deaths since, until the crate is emptied
     * or taken away ({@link #removeMarker}). Returns its id.
     */
    public static String crateMarker(ServerPlayer player, ServerLevel level, BlockPos at) {
        return deathMarker(player, level, at, true);
    }

    /** Takes a marker away (a Death Crate's, once it is emptied), for everyone who saw it. */
    public static void removeMarker(MinecraftServer server, String id) {
        if (id == null || id.isEmpty()) return;
        MapMarkers markers = MapMarkers.get(server);
        MapMarker m = markers.byId(id);
        if (m == null) return;
        markers.all().remove(m);
        markers.setDirty();
        markersChanged(server, m);
    }

    private static String deathMarker(ServerPlayer player, ServerLevel level, BlockPos at, boolean crate) {
        MinecraftServer server = level.getServer();
        MapMarkers markers = MapMarkers.get(server);
        String id = player.getUUID().toString();
        // only the last few plain death markers are kept; one on a crate stays as long as the crate
        List<MapMarker> deaths = new ArrayList<>();
        for (MapMarker m : markers.all()) if (m.death && !m.crate && m.owner.equals(id)) deaths.add(m);
        deaths.sort((a, b) -> Long.compare(a.at, b.at));
        while (!crate && deaths.size() >= MapMarkers.DEATHS_KEPT) markers.all().remove(deaths.remove(0));
        MapMarker m = new MapMarker();
        m.id = UUID.randomUUID().toString();
        m.dimension = level.dimension().identifier().toString();
        m.x = at.getX();
        m.y = at.getY();
        m.z = at.getZ();
        m.name = "";
        m.death = true;
        m.crate = crate;
        m.owner = id;
        m.companyId = Companies.idOf(player);
        m.at = System.currentTimeMillis();
        markers.all().add(m);
        markers.setDirty();
        sendMarkers(player);
        return m.id;
    }

    // ------------------------------------------------------------------------------------------------ companies

    /** One company folded into another: its fog and company markers go with it. */
    public static void fold(MinecraftServer server, String from, String into) {
        MapFog.fold(from, into);
        MapMarkers markers = MapMarkers.get(server);
        for (MapMarker m : markers.all()) if (m.companyId.equals(from)) m.companyId = into;
        markers.setDirty();
        SEED.remove(from);
        SEED.remove(into);
        SEED_STAMP.remove(into);
    }
}
