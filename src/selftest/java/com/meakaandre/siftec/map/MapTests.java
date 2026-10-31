package com.meakaandre.siftec.map;

import com.meakaandre.mapmod.api.MapModApi;
import com.meakaandre.mapmod.api.MapPoi;
import com.meakaandre.mapmod.api.MovingPoi;
import com.meakaandre.mapmod.api.PoiArea;
import com.meakaandre.mapmod.api.PoiCategory;
import com.meakaandre.mapmod.map.MapGrid;
import com.meakaandre.mapmod.map.MapMarker;
import com.meakaandre.mapmod.map.MapPayloads;
import com.meakaandre.mapmod.map.MapServer;
import com.meakaandre.mapmod.map.MapTerrain;
import com.meakaandre.mapmod.map.MapTile;
import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.TestQueue;
import com.meakaandre.siftec.TestReport;
import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.company.CompanyData;
import com.meakaandre.siftec.hub.MapLock;
import com.meakaandre.siftec.hub.Milestone;
import com.meakaandre.siftec.hub.Milestones;
import com.meakaandre.siftec.mapapi.NodePois;
import com.meakaandre.siftec.mapapi.SiftecMap;
import com.meakaandre.siftec.net.CompanyState;
import com.meakaandre.siftec.node.Node;
import com.meakaandre.siftec.node.NodeMap;
import com.meakaandre.siftec.node.NodeType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * /siftec selftest map: SIFTEC on MapMod's map. A company is the map's group (and machines' fake players stay off the
 * map); Field Research gives terrain, the minimap and the fullscreen map; SIFTEC's categories come in the filter panel
 * in their order; geysers are found in recorded chunks and key buildings show only to their company; nodes show for
 * the resources the company's scanner knows, with their purity; a Radar Tower's sweep clears the fog over its range
 * and fills unloaded land from the generator; vehicles move on their company's map only. The map's own parts (fog,
 * tiles, markers, death markers, the locator bar) are MapMod's and tested there.
 * <p>
 * {@code /siftec worldtest map ...} drives the smoke run's client for its screenshots (see {@link Control}).
 * Every check prints "SELFTEST map"; failures say FAIL.
 */
public final class MapTests {
    private static final String A = "selftest_map_a", B = "selftest_map_b";
    private static final String MEMBER_1 = "00000000-0000-4000-8000-0000000000a1", MEMBER_2 = "00000000-0000-4000-8000-0000000000a2";
    /** Well away from everything else the selftests build. */
    private static final int X = -9000, Z = 7000;
    /** A second member for the smoke run's real player, who places a company marker for them. */
    private static final String MATE = "00000000-0000-4000-8000-00000000beef";

    /** What the map's remote control does on the client: the map's screen, a filter, a pin, a new marker (MapMod has no minimap). */
    public enum Action {
        OPEN, CLOSE, HIDE, SHOW, PIN_MARKER, EDIT
    }

    /**
     * Server to client, from the smoke run's {@code /siftec worldtest map}: opens or closes the map, centred and zoomed,
     * or switches a filter (key presses under the CI's virtual display are not reliable enough).
     */
    public record Control(Action action, String arg, int x, int z, float zoom) implements CustomPacketPayload {
        public static final Type<Control> TYPE = new Type<>(Siftec.id("map_control"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Control> CODEC = StreamCodec.of((buf, p) -> {
            buf.writeVarInt(p.action.ordinal());
            buf.writeUtf(p.arg);
            buf.writeVarInt(p.x);
            buf.writeVarInt(p.z);
            buf.writeFloat(p.zoom);
        }, buf -> new Control(Action.values()[buf.readVarInt()], buf.readUtf(64), buf.readVarInt(), buf.readVarInt(), buf.readFloat()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private static final TestQueue LATER = new TestQueue();
    private static final TestReport REPORT = new TestReport("SELFTEST map ");

    private MapTests() {
    }

    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(Control.TYPE, Control.CODEC);
        LATER.register();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
            Commands.literal("siftec").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("selftest").then(Commands.literal("map").executes(MapTests::run)))
                .then(Commands.literal("worldtest").then(Commands.literal("map")
                    .then(Commands.literal("setup").executes(MapTests::setup))
                    .then(Commands.literal("death").executes(context -> {
                        for (ServerPlayer p : context.getSource().getServer().getPlayerList().getPlayers()) {
                            MapModApi.deathMarker(p, p.level(), p.blockPosition(), false);
                        }
                        return 1;
                    }))
                    .then(Commands.literal("open").then(Commands.argument("zoom", FloatArgumentType.floatArg(0.01f, 4))
                        .executes(context -> control(context, Action.OPEN, "", FloatArgumentType.getFloat(context, "zoom")))))
                    .then(Commands.literal("close").executes(context -> control(context, Action.CLOSE, "", 0)))
                    .then(Commands.literal("edit").executes(context -> control(context, Action.EDIT, "", 0)))
                    .then(Commands.literal("hide").then(Commands.argument("filter", StringArgumentType.greedyString())
                        .executes(context -> control(context, Action.HIDE, StringArgumentType.getString(context, "filter"), 0))))
                    .then(Commands.literal("show").then(Commands.argument("filter", StringArgumentType.greedyString())
                        .executes(context -> control(context, Action.SHOW, StringArgumentType.getString(context, "filter"), 0))))))));
    }

    private static void later(ServerLevel level, int ticks, Runnable run) {
        LATER.add(level.getGameTime() + ticks, run);
    }

    private static void report(CommandSourceStack source, String text) {
        REPORT.report(source, text);
    }

    private static void expect(CommandSourceStack source, String what, boolean ok, String detail) {
        REPORT.expect(source, what, ok, detail);
    }

    private static Company company(CommandSourceStack source, String id, String... members) {
        CompanyData data = CompanyData.get(source.getServer());
        Company c = data.companies().get(id);
        if (c == null) {
            c = new Company();
            c.id = id;
            c.name = "Selftest " + id;
            data.companies().put(id, c);
        }
        c.members.addAll(List.of(members));
        Companies.save(source.getServer());
        return c;
    }

    private static MapMarker marker(String name, int x, int z, boolean shared) {
        MapMarker m = new MapMarker();
        m.name = name;
        m.x = x;
        m.y = 70;
        m.z = z;
        m.icon = 1;
        m.colour = 2;
        m.shared = shared;
        return m;
    }

    private static PoiArea area(long region) {
        int minX = MapGrid.keyX(region) * MapGrid.REGION_BLOCKS, minZ = MapGrid.keyZ(region) * MapGrid.REGION_BLOCKS;
        return new PoiArea(region, minX, minZ, minX + MapGrid.REGION_BLOCKS, minZ + MapGrid.REGION_BLOCKS);
    }

    private static int run(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        var server = source.getServer();
        ServerLevel level = server.overworld();
        REPORT.reset();

        // ---- the unlock
        Milestone field = Milestones.get("field_research");
        boolean oldToken = false;
        for (Milestone m : Milestones.all()) oldToken |= m.tokens().contains("minimap");
        expect(source, "Field Research gives the Map; no milestone still gives Xaero's minimap token",
            field != null && field.tokens().contains(MapLock.TOKEN) && !oldToken, "");
        Company fresh = new Company();
        Company researched = new Company();
        researched.done.add("field_research");
        expect(source, "the minimap and fullscreen map are closed before Field Research and open after it",
            !MapLock.open(fresh) && MapLock.open(researched) && !MapLock.open(null), "");
        expect(source, "the map's unlocks are SIFTEC's: Field Research or nothing, with SIFTEC's message",
            MapModApi.unlocks() == SiftecMap.UNLOCKS && "siftec.map.locked".equals(SiftecMap.UNLOCKS.lockedMessage(FakePlayer.get(level))), "");

        // ---- companies are the map's groups
        Company a = company(source, A, MEMBER_1, MEMBER_2);
        Company b = company(source, B);
        expect(source, "a company is the map's group: its members share one, another company has its own",
            MapModApi.groups().groupOf(server, UUID.fromString(MEMBER_1)).equals(A) && MapModApi.groups().same(MapModApi.groups().groupOf(server, UUID.fromString(MEMBER_2)), A)
                && !MapModApi.groups().same(A, B) && MapModApi.groups().groupOf(server, UUID.randomUUID()).isEmpty(), "");
        expect(source, "machines' fake players stay off the map", MapModApi.groups().ignored(FakePlayer.get(level)), "");
        List<PoiCategory> cats = MapModApi.categories();
        expect(source, "the filter panel has SIFTEC's categories, after the map's own, in Satisfactory's order",
            cats.size() == 8 && cats.subList(2, 8).equals(List.of(SiftecMap.NODES, SiftecMap.WELLS, SiftecMap.GEYSERS, SiftecMap.CRASH_SITES, SiftecMap.ARTIFACTS, SiftecMap.BUILDINGS)),
            cats.size() + " categories");

        // ---- found in recorded chunks: geysers for everyone, key buildings for their company
        LevelChunk chunk = level.getChunk(X >> 4, Z >> 4);
        BlockPos geyser = new BlockPos((X & ~15) + 8, level.getMinY() + 12, (Z & ~15) + 8);
        level.setBlock(geyser, Blocks.POTENT_SULFUR.defaultBlockState(), 3);
        MapTerrain.record(level, chunk);
        long region = MapGrid.regionOfChunk(X >> 4, Z >> 4);
        boolean found = MapModApi.foundIn(region, B).stream().anyMatch(p -> p.kind().equals(SiftecMap.GEYSER.key()) && p.x() == geyser.getX() && p.z() == geyser.getZ());
        expect(source, "a sulfur geyser in a recorded chunk is found for the map", found, "");
        boolean noBuildings = MapModApi.foundIn(region, B).stream().noneMatch(p -> MapModApi.kind(p.kind()) != null && MapModApi.kind(p.kind()).category() == SiftecMap.BUILDINGS);
        expect(source, "no other company's key buildings show", noBuildings, "");

        // ---- nodes: those of resources the company's scanner knows
        if (NodeMap.ready()) {
            Optional<Node> iron = NodeMap.nearest(level, X, Z, NodeType.IRON, 12);
            if (iron.isPresent()) {
                long nodeRegion = MapGrid.regionOfBlock(iron.get().x(), iron.get().z());
                List<MapPoi> pois = NodePois.seedPois(level, area(nodeRegion), NodePois.Knows.of(b));
                expect(source, "an iron node shows on the map, with its purity", pois.stream().anyMatch(p -> p.kind().equals(SiftecMap.NODE.key()) && p.sub().equals("iron")
                    && p.x() == iron.get().x() && p.z() == iron.get().z() && p.purity() == iron.get().purity().ordinal()), "");
                boolean noUnknown = pois.stream().noneMatch(p -> p.kind().equals(SiftecMap.NODE.key()) && !NodeType.byId(p.sub()).onScanner(b));
                expect(source, "nodes the scanner does not know yet stay off the map", noUnknown, "");
            } else {
                report(source, "no iron node near the test spot");
            }
        }

        // ---- a Radar Tower's sweep clears the company's fog and fills unloaded land with the generator's guess
        int rx = X + 4000, rz = Z;
        MapModApi.reveal(level, b.id, new BlockPos(rx, 80, rz), 1056, true);
        boolean near = MapModApi.explored(B, (rx + 1000) >> 4, rz >> 4), beyond = MapModApi.explored(B, (rx + 1200) >> 4, rz >> 4);
        expect(source, "a Radar Tower's sweep clears the company's fog over about a kilometre", near && !beyond && MapModApi.explored(B, rx >> 4, (rz - 900) >> 4)
            && !MapModApi.explored(A, rx >> 4, rz >> 4), "");
        later(level, 200, () -> {
            MapTile t = MapTerrain.tile(MapGrid.regionOfBlock(rx + 700, rz));
            int px = Math.floorMod(rx + 700, MapGrid.REGION_BLOCKS) / MapGrid.PX_BLOCKS, pz = Math.floorMod(rz, MapGrid.REGION_BLOCKS) / MapGrid.PX_BLOCKS;
            boolean guessed = t != null && t.known(MapTile.index(px, pz));
            expect(source, "land nobody loaded in the sweep is filled in from the world generator", guessed, "");
            report(source, "done (later checks): " + REPORT.totals());
        });

        // ---- vehicles: on their company's map only
        FakePlayer fake = FakePlayer.get(level);
        List<MovingPoi> none = MapModApi.movingSources().getFirst().moving(fake, A);
        expect(source, "the map's moving things are SIFTEC's vehicles and drones (one kind each)",
            SiftecMap.MOVING.containsKey("drone") && SiftecMap.MOVING.size() > 1 && none.stream().allMatch(m -> SiftecMap.MOVING.containsValue(MapModApi.kind(m.kind()))), none.size() + " moving");

        report(source, "done: " + REPORT.totals() + " (a later check follows)");
        return REPORT.failed == 0 ? 1 : 0;
    }

    // ------------------------------------------------------------------------------------------------ the smoke run

    /**
     * Gives every online player's company the Map and its scanner, sweeps a Radar round them, and has a second
     * company member (not online) place a shared marker near them, which reaches their client like any other.
     */
    private static int setup(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        var server = source.getServer();
        ServerLevel level = server.overworld();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Company company = Companies.of(player);
            company.done.add("field_research");
            for (Milestone m : Milestones.all()) {
                for (String t : m.tokens()) if (t.startsWith("scanner:") || t.startsWith("object:")) company.done.add(m.id());
            }
            company.members.add(MATE);
            Companies.save(server);
            CompanyState.recompute(server);
            BlockPos at = player.blockPosition();
            MapModApi.reveal(level, company.id, at, 1056, true);
            MapServer.apply(server, MATE, company.id, MapPayloads.ADD, marker("Placed by a company mate", at.getX() + 60, at.getZ() - 40, true));
            MapMarker mine = marker("My marker", at.getX() - 70, at.getZ() + 30, false);
            mine.icon = 2;
            mine.colour = 4;
            MapServer.apply(server, player.getUUID().toString(), company.id, MapPayloads.ADD, mine);
            report(source, "map set up round " + at.toShortString() + " for " + player.getGameProfile().name());
        }
        return 1;
    }

    private static int control(CommandContext<CommandSourceStack> context, Action action, String arg, float zoom) {
        for (ServerPlayer player : context.getSource().getServer().getPlayerList().getPlayers()) {
            BlockPos at = player.blockPosition();
            if (!Companies.isFake(player) && ServerPlayNetworking.canSend(player, Control.TYPE)) {
                ServerPlayNetworking.send(player, new Control(action, arg, at.getX(), at.getZ(), zoom));
            }
        }
        return 1;
    }
}
