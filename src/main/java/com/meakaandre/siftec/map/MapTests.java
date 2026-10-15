package com.meakaandre.siftec.map;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.company.CompanyData;
import com.meakaandre.siftec.hub.MapLock;
import com.meakaandre.siftec.hub.Milestone;
import com.meakaandre.siftec.hub.Milestones;
import com.meakaandre.siftec.net.CompanyState;
import com.meakaandre.siftec.node.Node;
import com.meakaandre.siftec.node.NodeMap;
import com.meakaandre.siftec.node.NodeType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.gamerules.GameRules;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * /siftec selftest map (work package 15): Field Research gives the Map; the locator bar is off; fog is kept per
 * company; chunks are recorded into low-resolution tiles that pack and unpack whole; a Radar Tower's sweep clears
 * the fog over its range and fills unloaded land from the generator; geysers are found in recorded chunks; nodes
 * show for the scanner's resources; markers are private or shared, and a second member of the company sees, changes
 * and removes a shared one; death markers keep the last three.
 * <p>
 * {@code /siftec worldtest map ...} drives the smoke run's client for its screenshots (see {@link MapPayloads.Control}).
 * Every check prints "SELFTEST map"; failures say FAIL.
 */
public final class MapTests {
    private static final String A = "selftest_map_a", B = "selftest_map_b";
    private static final String MEMBER_1 = "00000000-0000-4000-8000-0000000000a1", MEMBER_2 = "00000000-0000-4000-8000-0000000000a2";
    /** Well away from everything else the selftests build. */
    private static final int X = -9000, Z = 7000;
    /** A second member for the smoke run's real player, who places a company marker for them. */
    private static final String MATE = "00000000-0000-4000-8000-00000000beef";

    private record Later(long at, Runnable run) {
    }

    private static final List<Later> LATER = new ArrayList<>();
    private static int passed, failed;

    private MapTests() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            long now = server.overworld().getGameTime();
            for (Later l : new ArrayList<>(LATER)) {
                if (l.at > now) continue;
                LATER.remove(l);
                l.run.run();
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> LATER.clear());
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
            Commands.literal("siftec").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("selftest").then(Commands.literal("map").executes(MapTests::run)))
                .then(Commands.literal("worldtest").then(Commands.literal("map")
                    .then(Commands.literal("setup").executes(MapTests::setup))
                    .then(Commands.literal("death").executes(context -> {
                        for (ServerPlayer p : context.getSource().getServer().getPlayerList().getPlayers()) MapServer.deathMarker(p);
                        return 1;
                    }))
                    .then(Commands.literal("open").then(Commands.argument("zoom", FloatArgumentType.floatArg(0.01f, 4))
                        .executes(context -> control(context, "open", "", FloatArgumentType.getFloat(context, "zoom")))))
                    .then(Commands.literal("close").executes(context -> control(context, "close", "", 0)))
                    .then(Commands.literal("edit").executes(context -> control(context, "edit", "", 0)))
                    .then(Commands.literal("hide").then(Commands.argument("filter", StringArgumentType.string())
                        .executes(context -> control(context, "hide", StringArgumentType.getString(context, "filter"), 0))))
                    .then(Commands.literal("show").then(Commands.argument("filter", StringArgumentType.string())
                        .executes(context -> control(context, "show", StringArgumentType.getString(context, "filter"), 0))))))));
    }

    private static void later(ServerLevel level, int ticks, Runnable run) {
        LATER.add(new Later(level.getGameTime() + ticks, run));
    }

    private static void report(CommandSourceStack source, String text) {
        String line = "SELFTEST map " + text;
        Siftec.LOGGER.info(line);
        source.sendSuccess(() -> Component.literal(line), false);
    }

    private static void expect(CommandSourceStack source, String what, boolean ok, String detail) {
        if (ok) passed++;
        else failed++;
        report(source, (ok ? "PASS " : "FAIL ") + what + (detail.isEmpty() ? "" : " (" + detail + ")"));
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
        m.company = shared;
        return m;
    }

    private static int run(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        passed = 0;
        failed = 0;

        // ---- the unlock and the locator bar
        Milestone field = Milestones.get("field_research");
        boolean oldToken = false;
        for (Milestone m : Milestones.all()) oldToken |= m.tokens().contains("minimap");
        expect(source, "Field Research gives the Map; no milestone still gives Xaero's minimap token",
            field != null && field.tokens().contains(MapServer.TOKEN) && !oldToken, "");
        Company fresh = new Company();
        Company researched = new Company();
        researched.done.add("field_research");
        expect(source, "the minimap and fullscreen map are closed before Field Research and open after it",
            !MapServer.unlocked(fresh) && MapServer.unlocked(researched) && MapLock.open(researched) && !MapLock.open(null), "");
        expect(source, "vanilla's player locator bar is switched off", !source.getServer().getGameRules().get(GameRules.LOCATOR_BAR), "");

        // ---- fog is per company, and shared inside it
        Company a = company(source, A, MEMBER_1, MEMBER_2);
        Company b = company(source, B);
        it.unimi.dsi.fastutil.longs.LongOpenHashSet fresh1 = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        var cleared = MapFog.reveal(A, X >> 4, Z >> 4, MapServer.VISIT_RADIUS, fresh1);
        expect(source, "a member's visit clears a disc of chunks for their company",
            !cleared.isEmpty() && MapFog.explored(A, X >> 4, Z >> 4) && MapFog.explored(A, (X >> 4) + 5, Z >> 4) && !MapFog.explored(A, (X >> 4) + 8, (Z >> 4) + 8),
            cleared.size() + " chunks");
        expect(source, "another company's fog is its own", !MapFog.explored(B, X >> 4, Z >> 4), "");

        // ---- terrain: a loaded chunk is recorded at 4 by 4 pixels, and the tile packs and unpacks whole
        LevelChunk chunk = level.getChunk(X >> 4, Z >> 4);
        BlockPos geyser = new BlockPos((X & ~15) + 8, level.getMinY() + 12, (Z & ~15) + 8);
        level.setBlock(geyser, Blocks.POTENT_SULFUR.defaultBlockState(), 3);
        MapTerrain.record(level, chunk);
        long region = MapGrid.regionOfChunk(X >> 4, Z >> 4);
        MapTile tile = MapTerrain.tile(region);
        int known = 0;
        if (tile != null) {
            int bx = Math.floorMod(X >> 4, MapGrid.REGION_CHUNKS) * MapGrid.CHUNK_PX, bz = Math.floorMod(Z >> 4, MapGrid.REGION_CHUNKS) * MapGrid.CHUNK_PX;
            for (int pz = 0; pz < MapGrid.CHUNK_PX; pz++) for (int px = 0; px < MapGrid.CHUNK_PX; px++) if (tile.known(MapTile.index(bx + px, bz + pz)) && !tile.guessed(MapTile.index(bx + px, bz + pz))) known++;
        }
        expect(source, "a loaded chunk is recorded as 16 pixels", known == 16, known + " of 16");
        boolean same = false;
        int bytes = 0;
        if (tile != null) {
            try {
                byte[] packed = tile.pack();
                bytes = packed.length;
                MapTile back = MapTile.unpack(packed);
                same = back.version == tile.version && Arrays.equals(back.colour, tile.colour) && Arrays.equals(back.height, tile.height);
            } catch (IOException e) {
                same = false;
            }
        }
        expect(source, "a tile packs small and unpacks the same", same && bytes < 120_000, bytes + " bytes");
        boolean found = MapTerrain.foundFor(region, B).stream().anyMatch(p -> p.kind() == MapPoi.Kind.GEYSER && p.x() == geyser.getX() && p.z() == geyser.getZ());
        expect(source, "a sulfur geyser in a recorded chunk is found for the map", found, "");
        boolean noBuildings = MapTerrain.foundFor(region, B).stream().noneMatch(p -> p.kind().category == MapPoi.Category.BUILDINGS);
        expect(source, "no other company's key buildings show", noBuildings, "");

        // ---- nodes: those of resources the company's scanner knows
        if (NodeMap.ready(level)) {
            Optional<Node> iron = NodeMap.nearest(level, X, Z, NodeType.IRON, 12);
            if (iron.isPresent()) {
                long nodeRegion = MapGrid.regionOfBlock(iron.get().x(), iron.get().z());
                List<MapPoi> pois = MapServer.poisFor(level, b, nodeRegion);
                expect(source, "an iron node shows on the map, with its purity", pois.stream().anyMatch(p -> p.kind() == MapPoi.Kind.NODE && p.sub().equals("iron")
                    && p.x() == iron.get().x() && p.z() == iron.get().z() && p.purity() == iron.get().purity().ordinal()), "");
                boolean noUnknown = pois.stream().noneMatch(p -> p.kind() == MapPoi.Kind.NODE && !NodeType.byId(p.sub()).onScanner(b));
                expect(source, "nodes the scanner does not know yet stay off the map", noUnknown, "");
            } else {
                report(source, "no iron node near the test spot");
            }
        }

        // ---- a Radar Tower's sweep clears its range and fills unloaded land with the generator's guess
        int rx = X + 4000, rz = Z;
        MapServer.radarSweep(level, b, new BlockPos(rx, 80, rz), 1056);
        boolean near = MapFog.explored(B, (rx + 1000) >> 4, rz >> 4), beyond = MapFog.explored(B, (rx + 1200) >> 4, rz >> 4);
        expect(source, "a Radar Tower's sweep clears the company's fog over about a kilometre", near && !beyond && MapFog.explored(B, rx >> 4, (rz - 900) >> 4), "");
        later(level, 200, () -> {
            MapTile t = MapTerrain.tile(MapGrid.regionOfBlock(rx + 700, rz));
            int px = Math.floorMod(rx + 700, MapGrid.REGION_BLOCKS) / MapGrid.PX_BLOCKS, pz = Math.floorMod(rz, MapGrid.REGION_BLOCKS) / MapGrid.PX_BLOCKS;
            boolean guessed = t != null && t.known(MapTile.index(px, pz));
            expect(source, "land nobody loaded in the sweep is filled in from the world generator", guessed, "");
            report(source, "done (later checks): " + passed + " passed, " + failed + " failed");
        });

        // ---- markers: private, shared, and a second member of the company
        var server = source.getServer();
        MapMarkers markers = MapMarkers.get(server);
        markers.all().removeIf(m -> m.owner.equals(MEMBER_1) || m.owner.equals(MEMBER_2));
        String r1 = MapServer.apply(server, MEMBER_1, A, MapPayloads.ADD, marker("Iron here", X + 10, Z, true));
        String r2 = MapServer.apply(server, MEMBER_1, A, MapPayloads.ADD, marker("My secret", X + 20, Z, false));
        MapMarker shared = markers.visibleTo(MEMBER_2, A).stream().filter(m -> m.name.equals("Iron here")).findFirst().orElse(null);
        expect(source, "a company marker reaches the company's other member; a private one does not",
            r1 == null && r2 == null && shared != null && markers.visibleTo(MEMBER_2, A).stream().noneMatch(m -> m.name.equals("My secret"))
                && markers.visibleTo(MEMBER_1, A).size() == 2, "");
        expect(source, "no other company sees either", markers.visibleTo("someone-else", B).stream().noneMatch(m -> m.owner.equals(MEMBER_1)), "");
        if (shared != null) {
            MapMarker rename = marker("Iron, pure", 0, 0, true);
            rename.id = shared.id;
            String r3 = MapServer.apply(server, MEMBER_2, A, MapPayloads.UPDATE, rename);
            expect(source, "the other member changes the shared marker and the first sees the change",
                r3 == null && markers.visibleTo(MEMBER_1, A).stream().anyMatch(m -> m.id.equals(shared.id) && m.name.equals("Iron, pure") && m.x == X + 10), "");
            MapMarker secret = markers.visibleTo(MEMBER_1, A).stream().filter(m -> m.name.equals("My secret")).findFirst().orElse(null);
            MapMarker hit = new MapMarker();
            hit.id = secret == null ? "" : secret.id;
            String r4 = MapServer.apply(server, MEMBER_2, A, MapPayloads.DELETE, hit);
            String r5 = MapServer.apply(server, "someone-else", B, MapPayloads.DELETE, rename);
            expect(source, "nobody else can remove a private marker, and no other company a shared one",
                "siftec.map.not_yours".equals(r4) && "siftec.map.not_yours".equals(r5) && markers.byId(shared.id) != null, r4 + ", " + r5);
            String r6 = MapServer.apply(server, MEMBER_2, A, MapPayloads.DELETE, rename);
            expect(source, "the other member removes the shared marker for both", r6 == null && markers.byId(shared.id) == null, "");
        }

        // ---- death markers: private, the last three kept
        FakePlayer fake = FakePlayer.get(level);
        String fakeId = fake.getUUID().toString();
        markers.all().removeIf(m -> m.owner.equals(fakeId));
        fake.setPos(X + 0.5, 90, Z + 0.5);
        for (int i = 0; i < 4; i++) MapServer.deathMarker(fake);
        long deaths = markers.all().stream().filter(m -> m.death && m.owner.equals(fakeId)).count();
        boolean placedRight = markers.all().stream().anyMatch(m -> m.death && m.owner.equals(fakeId) && m.x == X && m.z == Z && !m.company);
        expect(source, "dying leaves a private death marker where it happened; the last three are kept", deaths == 3 && placedRight, deaths + " kept");
        markers.all().removeIf(m -> m.owner.equals(fakeId) || m.owner.equals(MEMBER_1) || m.owner.equals(MEMBER_2));
        markers.setDirty();

        report(source, "done: " + passed + " passed, " + failed + " failed (a later check follows)");
        return failed == 0 ? 1 : 0;
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
            MapServer.radarSweep(level, company, at, 1056);
            MapServer.apply(server, MATE, company.id, MapPayloads.ADD, marker("Placed by a company mate", at.getX() + 60, at.getZ() - 40, true));
            MapMarker mine = marker("My marker", at.getX() - 70, at.getZ() + 30, false);
            mine.icon = 2;
            mine.colour = 4;
            MapServer.apply(server, player.getUUID().toString(), company.id, MapPayloads.ADD, mine);
            report(source, "map set up round " + at.toShortString() + " for " + player.getGameProfile().name());
        }
        return 1;
    }

    private static int control(CommandContext<CommandSourceStack> context, String action, String arg, float zoom) {
        for (ServerPlayer player : context.getSource().getServer().getPlayerList().getPlayers()) {
            BlockPos at = player.blockPosition();
            MapServer.control(player, new MapPayloads.Control(action, arg, at.getX(), at.getZ(), zoom));
        }
        return 1;
    }
}
