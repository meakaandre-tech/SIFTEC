package com.meakaandre.siftec.command;

import com.meakaandre.siftec.TestReport;
import com.meakaandre.siftec.block.NodeBlock;
import com.meakaandre.siftec.building.BuildingLogic;
import com.meakaandre.siftec.building.BuildingPower;
import com.meakaandre.siftec.collect.Collectibles;
import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.geyser.Vents;
import com.meakaandre.siftec.hub.HubBlockEntity;
import com.meakaandre.siftec.node.Node;
import com.meakaandre.siftec.node.NodeMap;
import com.meakaandre.siftec.node.NodePlacer;
import com.meakaandre.siftec.node.NodeSavedData;
import com.meakaandre.siftec.node.NodeSites;
import com.meakaandre.siftec.node.NodeType;
import com.meakaandre.siftec.node.Terrain;
import com.meakaandre.siftec.power.PowerTests;
import com.meakaandre.siftec.registry.ModBlocks;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PotentSulfurBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.PotentSulfurState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Selftests for how the mod fits the world: nodes, scanners, sulfur caves, geysers, Power Storage, Furnace Engines,
 * processors and Power Lines. Run by the automated test on an ordinary world. Every line of
 * output starts with "SELFTEST world".
 */
public final class WorldTests {
    private static final TestReport REPORT = new TestReport("SELFTEST world ");

    private WorldTests() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            var seconds = Commands.argument("seconds", IntegerArgumentType.integer(10, 600));
            var geyserAt = Commands.argument("x", IntegerArgumentType.integer())
                .then(Commands.argument("y", IntegerArgumentType.integer())
                    .then(Commands.argument("z", IntegerArgumentType.integer())
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(10, 600)).executes(c -> geyser(c, new BlockPos(
                            IntegerArgumentType.getInteger(c, "x"), IntegerArgumentType.getInteger(c, "y"), IntegerArgumentType.getInteger(c, "z")))))));
            var worldtest = Commands.literal("worldtest")
                .then(Commands.literal("nodes").then(Commands.argument("radius", IntegerArgumentType.integer(128, 8000)).executes(WorldTests::nodes)))
                .then(Commands.literal("place").then(Commands.argument("type", StringArgumentType.word()).executes(WorldTests::place)))
                .then(Commands.literal("geyser").then(Commands.literal("build").then(seconds.executes(c -> geyser(c, null))))
                    .then(Commands.literal("natural").then(Commands.argument("seconds", IntegerArgumentType.integer(10, 600)).executes(WorldTests::naturalGeyser)))
                    .then(Commands.literal("wet").then(Commands.argument("seconds", IntegerArgumentType.integer(10, 600)).executes(WorldTests::wetGeyser))).then(geyserAt))
                .then(Commands.literal("hubui").executes(WorldTests::hubUi))
                .then(Commands.literal("geysers").then(Commands.argument("dx", IntegerArgumentType.integer()).then(Commands.argument("dz", IntegerArgumentType.integer())
                    .then(Commands.argument("half", IntegerArgumentType.integer(16, 1500)).executes(WorldTests::geyserSurvey)))));
            dispatcher.register(Commands.literal("siftec").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).then(worldtest));
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (var it = MONITORS.iterator(); it.hasNext(); ) {
                Monitor monitor = it.next();
                if (monitor.tick()) it.remove();
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            MONITORS.clear();
            SITES.clear();
            NATURAL.clear();
            WET.clear();
        });
    }

    /**
     * For the client screenshots of the HUB screen: an old-style single-block HUB terminal on a 9 x 9 stone brick floor
     * in front of every player, who is turned to face it. Their company starts over at Tier 0.
     */
    private static int hubUi(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        int x = 24, z = 24;
        level.getChunkAt(new BlockPos(x, 0, z));
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 6; dz++) {
                level.setBlockAndUpdate(new BlockPos(x + dx, y - 1, z + dz), Math.abs(dx) <= 4 && Math.abs(dz) <= 4 ? Blocks.STONE_BRICKS.defaultBlockState() : Blocks.SMOOTH_STONE.defaultBlockState());
                for (int dy = 0; dy < 4; dy++) level.setBlockAndUpdate(new BlockPos(x + dx, y + dy, z + dz), Blocks.AIR.defaultBlockState());
            }
        }
        BlockPos hubPos = new BlockPos(x, y, z);
        level.setBlockAndUpdate(hubPos, ModBlocks.HUB.get().defaultBlockState());
        for (ServerPlayer player : source.getServer().getPlayerList().getPlayers()) {
            Company company = Companies.of(player);
            company.done.clear();
            company.paid.clear();
            company.active = "";
            company.lockUntil = 0;
            Companies.save(source.getServer());
            if (level.getBlockEntity(hubPos) instanceof HubBlockEntity hub) {
                hub.companyId = company.id;
                hub.setChanged();
                report(source, "hubui terminal for " + company.name);
            }
            // three blocks south of the HUB, looking north and down at it
            player.teleportTo(level, x + 0.5, y, z + 3.5, Set.of(), 180f, 22f, true);
        }
        return 1;
    }

    private static void report(CommandSourceStack source, String text) {
        REPORT.report(source, text);
    }

    private static BlockPos origin(ServerLevel level) {
        int[] o = NodeMap.origin();
        return o == null ? BlockPos.ZERO : new BlockPos(o[0], 0, o[1]);
    }

    /** A place to build tests: beside spawn, well above the ground, its chunk kept loaded while in use. */
    private static BlockPos site(ServerLevel level, int dx, int dz) {
        // the same spot for a setup and its check, even though the setup built on it
        return SITES.computeIfAbsent(dx + "," + dz, k -> findSite(level, dx, dz));
    }

    private static final Map<String, BlockPos> SITES = new HashMap<>();

    private static BlockPos findSite(ServerLevel level, int dx, int dz) {
        BlockPos o = origin(level);
        int x = o.getX() + dx, z = o.getZ() + dz;
        level.getChunk(x >> 4, z >> 4);
        int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        if (ground <= level.getMinY() + 1) ground = 64;
        return new BlockPos(x, ground + 12, z);
    }

    // ---- nodes and scanners

    private static int nodes(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        NodeMap.settle(level);
        BlockPos o = origin(level);
        Terrain.Border border = Terrain.Border.of(level);
        // first scans, from cold: what a scanner costs the first time in a fresh world
        NodeMap.resetCaches();
        for (NodeType type : NodeType.values()) {
            if (type.dimension() != level.dimension()) continue;
            long samples = NodeMap.SAMPLES.get(), start = System.nanoTime();
            Optional<Node> found = NodeMap.nearest(level, o.getX(), o.getZ(), type, 64, border, Long.MAX_VALUE);
            long ms = (System.nanoTime() - start) / 1_000_000;
            report(source, "scan " + type.id() + ": " + found.map(n -> "at " + n.x() + "," + (n.y() == Node.SURFACE ? "~" : n.y()) + "," + n.z() + " distance "
                + Math.round(n.distanceTo(o.getX(), o.getZ())) + " " + (land(level, n) ? "on land" : "NOT ON LAND")).orElse("none")
                + " in " + ms + " ms, " + (NodeMap.SAMPLES.get() - samples) + " biome samples");
        }
        // every node within the radius: counted by type, and checked against the generator: land, no water, a real sulfur cave
        int radius = IntegerArgumentType.getInteger(context, "radius");
        int cells = radius / NodeMap.CELL;
        Map<NodeType, Integer> count = new EnumMap<>(NodeType.class);
        int bad = 0, total = 0;
        List<String> badList = new ArrayList<>();
        long start = System.nanoTime();
        int cx0 = Math.floorDiv(o.getX(), NodeMap.CELL), cz0 = Math.floorDiv(o.getZ(), NodeMap.CELL);
        for (int dx = -cells; dx <= cells; dx++) {
            for (int dz = -cells; dz <= cells; dz++) {
                for (Node node : NodeMap.inCell(level, cx0 + dx, cz0 + dz)) {
                    total++;
                    count.merge(node.type(), 1, Integer::sum);
                    if (!land(level, node)) {
                        bad++;
                        if (badList.size() < 10) badList.add(node.toString());
                    }
                }
            }
        }
        report(source, "census radius " + radius + " (" + (2 * cells + 1) * (2 * cells + 1) + " cells) in " + (System.nanoTime() - start) / 1_000_000 + " ms: "
            + total + " nodes " + count + "; not on land / over water / outside a sulfur cave: " + bad + " " + badList);
        // what the scanner reports, from points around spawn: always on land
        int checks = 0, onLand = 0;
        for (int i = 0; i < 8; i++) {
            double angle = i * Math.PI / 4;
            double x = o.getX() + Math.cos(angle) * radius / 2.0, z = o.getZ() + Math.sin(angle) * radius / 2.0;
            for (NodeType type : NodeType.values()) {
                if (type.dimension() != level.dimension()) continue;
                Optional<Node> found = NodeMap.nearest(level, x, z, type, 16, border, NodeMap.SEARCH_NANOS);
                if (found.isEmpty()) continue;
                checks++;
                if (land(level, found.get())) onLand++;
            }
        }
        report(source, "scanner results checked " + checks + ", on land " + onLand);
        // collectibles: on land too
        int spots = 0, spotsOnLand = 0;
        for (int dx = -10; dx <= 10; dx++) {
            for (int dz = -10; dz <= 10; dz++) {
                var spot = Collectibles.inCell(level, Math.floorDiv(o.getX(), Collectibles.CELL) + dx,
                    Math.floorDiv(o.getZ(), Collectibles.CELL) + dz);
                if (spot.isEmpty()) continue;
                spots++;
                Terrain.Column column = Terrain.column(level, spot.get().x(), spot.get().z());
                if (column != null && !column.water()) spotsOnLand++;
            }
        }
        report(source, "collectible spots within 1000: " + spots + ", on land " + spotsOnLand);
        return 1;
    }

    /** By the generator: a surface node over dry ground, a sulfur node inside a sulfur cave, a Nether node anywhere. */
    private static boolean land(ServerLevel level, Node node) {
        if (node.type() == NodeType.QUARTZ) return true;
        if (node.type() == NodeType.SULFUR) {
            return Terrain.biome(level, node.x(), node.y(), node.z()).unwrapKey().map(k -> k.identifier().getPath().equals("sulfur_caves")).orElse(false);
        }
        Terrain.Column column = Terrain.column(level, node.x(), node.z());
        return column != null && !column.water();
    }

    /**
     * A geyser the world generated itself: the sulfur pools of the sulfur cave under the nearest sulfur node hold
     * potent sulfur under water. Searches the chunks round that node and watches the first geyser found.
     */
    private static int naturalGeyser(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        BlockPos o = origin(level);
        if (!NATURAL.isEmpty()) {
            BlockPos found = NATURAL.getFirst();
            report(source, "geyser natural: the nearest periodic geyser the surveys found, at " + found.toShortString() + ", " + Math.round(Math.sqrt(found.distSqr(new BlockPos(o.getX(), found.getY(), o.getZ()))))
                + " blocks from spawn");
            return geyser(context, found);
        }
        Optional<Node> sulfur = NodeMap.nearest(level, o.getX(), o.getZ(), NodeType.SULFUR, 64, Terrain.Border.of(level), Long.MAX_VALUE);
        if (sulfur.isEmpty() || sulfur.get().y() == Node.SURFACE) {
            report(source, "geyser natural: no sulfur cave found");
            return 0;
        }
        Node node = sulfur.get();
        long start = System.nanoTime();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos best = null, dry = null;
        double bestDistance = Double.MAX_VALUE;
        int chunks = 0, potent = 0, wet = 0, sulfurBlocks = 0, caveBiome = 0;
        BlockPos middle = new BlockPos(node.x(), node.y(), node.z());
        for (int r = 0; r <= 3 && best == null; r++) {
            for (int cx = (node.x() >> 4) - r; cx <= (node.x() >> 4) + r; cx++) {
                for (int cz = (node.z() >> 4) - r; cz <= (node.z() >> 4) + r; cz++) {
                    if (Math.max(Math.abs(cx - (node.x() >> 4)), Math.abs(cz - (node.z() >> 4))) != r) continue;
                    var chunk = level.getChunk(cx, cz);
                    chunks++;
                    if (Terrain.biome(level, (cx << 4) + 8, node.y(), (cz << 4) + 8).unwrapKey().map(k -> k.identifier().getPath().equals("sulfur_caves")).orElse(false)) caveBiome++;
                    // the whole height: pools lie on the cave's sulfur floor, springs on the ground above the cave
                    for (int y = level.getMinY(); y < level.getMaxY() - 1; y++) {
                        for (int x = 0; x < 16; x++) {
                            for (int z = 0; z < 16; z++) {
                                pos.set((cx << 4) + x, y, (cz << 4) + z);
                                BlockState state = chunk.getBlockState(pos);
                                if (state.is(Blocks.SULFUR)) sulfurBlocks++;
                                if (!state.is(Blocks.POTENT_SULFUR)) continue;
                                potent++;
                                if (!chunk.getFluidState(pos.above()).isSourceOfType(Fluids.WATER)) {
                                    if (dry == null) dry = pos.immutable();
                                    continue;
                                }
                                wet++;
                                double d = pos.distSqr(middle);
                                if (d < bestDistance) {
                                    bestDistance = d;
                                    best = pos.immutable();
                                }
                            }
                        }
                    }
                }
            }
        }
        BlockPos chosen = best != null ? best : dry;
        report(source, "geyser natural: sulfur node " + node + ", searched " + chunks + " chunks (" + caveBiome + " with sulfur caves at the node's height) in "
            + (System.nanoTime() - start) / 1_000_000 + " ms: " + sulfurBlocks + " sulfur blocks, " + potent + " potent sulfur, " + wet + " of them under water; "
            + (chosen == null ? "no geyser found" : "world-generated geyser at " + chosen.toShortString() + (best == null ? " (dry)" : "") + " in "
            + Terrain.biome(level, chosen.getX(), chosen.getY(), chosen.getZ()).unwrapKey().map(k -> k.identifier().toString()).orElse("?")));
        if (chosen == null) return 0;
        return geyser(context, chosen);
    }

    /** Places the nearest node of a type (from spawn, or 150,150 in the Nether) and reports where its core is and what it stands on. */
    private static int place(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        NodeType type = NodeType.byId(StringArgumentType.getString(context, "type"));
        if (type == null) return 0;
        BlockPos o = level.dimension() == Level.NETHER ? new BlockPos(150, 0, 150) : origin(level);
        Optional<Node> found = NodeMap.nearest(level, o.getX(), o.getZ(), type, type == NodeType.SULFUR ? 24 : 32);
        if (found.isEmpty()) {
            report(source, "place " + type.id() + ": no node");
            return 0;
        }
        Node node = found.get();
        int cx = node.x() >> 4, cz = node.z() >> 4;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) level.setChunkForced(cx + dx, cz + dz, true);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) level.getChunk(cx + dx, cz + dz);
        NodePlacer.placeChunk(level, cx, cz);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) if (dx != 0 || dz != 0) NodePlacer.placeChunk(level, cx + dx, cz + dz);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(node.x(), level.getMaxY(), node.z());
        int saved = NodeSavedData.get(source.getServer()).height(node.key());
        if (saved != NodeSavedData.NO_HEIGHT && saved != NodePlacer.NO_PLACE) pos.setY(saved + 1);
        while (pos.getY() > level.getMinY()) {
            BlockState state = level.getBlockState(pos);
            if (state.getBlock() instanceof NodeBlock || NodeSites.isSite(level, pos)) break;
            pos.move(0, -1, 0);
        }
        String result;
        if (pos.getY() <= level.getMinY()) {
            result = "no core block (saved height " + (saved == NodePlacer.NO_PLACE ? "none fit" : saved) + ")";
        } else {
            BlockState under = level.getBlockState(pos.below());
            int solid = 0, around = 0;
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    around++;
                    BlockState s = level.getBlockState(pos.offset(dx, -1, dz));
                    if (!s.isAir() && s.getFluidState().isEmpty()) solid++;
                }
            }
            result = "core at " + pos.toShortString() + " on " + BuiltInRegistries.BLOCK.getKey(under.getBlock()) + ", solid under the mound " + solid + "/" + around
                + "; " + NodeTests.padReport(level, node);
        }
        report(source, "place " + type.id() + " " + node + ": " + result);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) level.setChunkForced(cx + dx, cz + dz, false);
        return 1;
    }

    // ---- geysers and Power Storage

    private static final List<Monitor> MONITORS = new ArrayList<>();

    private interface Monitor {
        /** True when finished. */
        boolean tick();
    }

    /**
     * Watches a geyser with a Geothermal Generator over it for a while (a Power Storage wired to it through a pole) and
     * reports: the eruptions and the time between them, whether the generator gave power outside an eruption, the
     * SU-seconds each eruption gave, the acid it made and what the storage banked. {@code vent} null: builds a geyser
     * beside spawn first. Whatever is in the generator's way is cleared first, as a player would.
     */
    private static int geyser(CommandContext<CommandSourceStack> context, BlockPos vent) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        int seconds = IntegerArgumentType.getInteger(context, "seconds");
        if (vent == null) {
            BlockPos base = site(level, 24, -24);
            level.setChunkForced(base.getX() >> 4, base.getZ() >> 4, true);
            // a stone basin: magma, the geyser, one block of water, air
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dy = -1; dy <= 2; dy++) level.setBlockAndUpdate(base.offset(dx, dy, dz), Blocks.STONE.defaultBlockState());
                }
            }
            level.setBlockAndUpdate(base, Blocks.MAGMA_BLOCK.defaultBlockState());
            level.setBlockAndUpdate(base.above(2), Blocks.WATER.defaultBlockState());
            level.setBlockAndUpdate(base.above(1), Block.updateFromNeighbourShapes(Blocks.POTENT_SULFUR.defaultBlockState(), level, base.above(1)));
            vent = base.above(1);
        }
        // the chunks round it, so they are fully ticking chunks and not only loaded ones
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) level.setChunkForced((vent.getX() >> 4) + dx, (vent.getZ() >> 4) + dz, true);
        level.getChunk(vent.getX() >> 4, vent.getZ() >> 4);
        BlockPos forcedAt = vent;
        BlockState ventState = level.getBlockState(vent);
        if (!ventState.is(Blocks.POTENT_SULFUR)) {
            report(source, "geyser " + vent.toShortString() + ": no geyser there (" + ventState + ")");
            return 0;
        }
        int depth = 0;
        while (depth < 6 && level.getFluidState(vent.above(depth + 1)).isSourceOfType(Fluids.WATER)) depth++;
        BlockPos onWater = vent.above(depth + 1);
        // the generator's vent cell (the middle of its bottom row, two in from the front) is the air over the water
        BlockPos anchor = onWater.south(2);
        for (int dx = -9; dx <= 9; dx++) {
            for (int dz = -6; dz <= 4; dz++) {
                for (int dy = 0; dy <= 13; dy++) {
                    BlockPos p = anchor.offset(dx, dy, dz);
                    if (!level.getBlockState(p).isAir() && !level.getFluidState(p).isSource()) level.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
                }
            }
        }
        var gen = PowerTests.build(level, source, "geothermal_generator", anchor, Direction.NORTH);
        var store = PowerTests.build(level, source, "power_storage", anchor.east(6), Direction.NORTH);
        var pole = PowerTests.build(level, source, "power_pole_mk1", anchor.west(5), Direction.NORTH);
        BlockPos finalVent = vent;
        report(source, "geyser " + vent.toShortString() + ": state " + ventState.getValue(PotentSulfurBlock.STATE).getSerializedName() + ", water " + depth
            + " deep; generator placed " + (gen != null) + ", it finds the vent " + (gen != null && finalVent.equals(BuildingPower.vent(level, gen)))
            + ", gas escapes " + Vents.gasEscapes(level, finalVent) + "; watching " + seconds + " s");
        MONITORS.add(new Monitor() {
            int ticks;
            long lastStart = -1;
            final List<Double> gaps = new ArrayList<>();
            final List<Double> bursts = new ArrayList<>();
            int eruptions, givingOutside, givingInside;
            double current;
            boolean wasErupting, wired;
            int waited;

            @Override
            public boolean tick() {
                if (!wired) {
                    wired = true;
                    var p = PowerTests.connector(level, pole);
                    PowerTests.wire(PowerTests.connector(level, gen), p);
                    PowerTests.wire(PowerTests.connector(level, store), p);
                }
                // the watch starts once the geyser's chunk really ticks (up to a minute after the chunk is forced)
                if (ticks == 0 && !level.shouldTickBlocksAt(finalVent) && ++waited < 1200) return false;
                ticks++;
                BlockState state = level.getBlockState(finalVent);
                boolean erupting = state.is(Blocks.POTENT_SULFUR) && state.getValue(PotentSulfurBlock.STATE) == PotentSulfurState.ERUPTING;
                double gave = gen == null ? 0 : gen.lastGenerated;
                if (erupting && !wasErupting) {
                    eruptions++;
                    if (lastStart >= 0) gaps.add((ticks - lastStart) / 20.0);
                    lastStart = ticks;
                }
                if (!erupting && wasErupting) {
                    bursts.add(Math.round(current / 20) * 1.0);
                    current = 0;
                }
                if (gave > 0) {
                    current += gave;
                    if (erupting) givingInside++;
                    else givingOutside++;
                }
                if (gen != null && !gen.running) gen.lastGenerated = 0;
                wasErupting = erupting;
                if (ticks < seconds * 20) return false;
                double min = gaps.stream().mapToDouble(Double::doubleValue).min().orElse(0), max = gaps.stream().mapToDouble(Double::doubleValue).max().orElse(0);
                report(source, "geyser " + finalVent.toShortString() + " after " + seconds + " s (chunk ticking after " + waited / 20 + " s): " + eruptions + " eruptions, gaps " + gaps
                    + " s (min " + min + ", max " + max + ", all within 20-70: " + gaps.stream().allMatch(g -> g >= 20 && g <= 70) + "); generator gave power " + givingInside
                    + " ticks during eruptions and " + givingOutside + " outside; SU-seconds per eruption " + bursts + "; generator now " + (gen == null ? "missing"
                    : BuildingLogic.status(level, gen).getString()) + "; storage " + (store == null ? "missing"
                    : BuildingLogic.status(level, store).getString()));
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) level.setChunkForced((forcedAt.getX() >> 4) + dx, (forcedAt.getZ() >> 4) + dz, false);
                return true;
            }
        });
        return 1;
    }

    // ---- natural geysers: how many the world really makes

    /** Periodic geysers (potent sulfur over magma, water above) found by the surveys, nearest to spawn first. */
    private static final List<BlockPos> NATURAL = new ArrayList<>();
    /** Wet potent sulfur (a sulfur pool's vent: water over it, no heat under it) found by the surveys, nearest first. */
    private static final List<BlockPos> WET = new ArrayList<>();

    /**
     * The nearest wet vent the surveys found, given a magma block underneath the way a player would: vanilla
     * turns it into a periodic geyser, and the engine is watched on it.
     */
    private static int wetGeyser(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        if (WET.isEmpty()) {
            report(source, "geyser wet: the surveys found no wet vent");
            return 0;
        }
        BlockPos vent = WET.getFirst();
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) level.setChunkForced((vent.getX() >> 4) + dx, (vent.getZ() >> 4) + dz, true);
        BlockState before = level.getBlockState(vent);
        String under = BuiltInRegistries.BLOCK.getKey(level.getBlockState(vent.below()).getBlock()).getPath();
        level.setBlockAndUpdate(vent.below(), Blocks.MAGMA_BLOCK.defaultBlockState());
        BlockState after = level.getBlockState(vent);
        report(source, "geyser wet: vent " + vent.toShortString() + " was " + (before.is(Blocks.POTENT_SULFUR) ? before.getValue(PotentSulfurBlock.STATE).getSerializedName() : before.toString())
            + " over " + under + "; with a magma block put under it, it is " + (after.is(Blocks.POTENT_SULFUR) ? after.getValue(PotentSulfurBlock.STATE).getSerializedName() : after.toString()));
        return geyser(context, vent);
    }

    /**
     * Counts the potent sulfur a stretch of the world generated. Vanilla only places it from features of the
     * sulfur_caves biome (its sulfur springs, rooted in the cave and grown up to the first open, level spot above,
     * and the wet sulfur pools on the cave floor), so only chunks with sulfur caves under them, and the chunks
     * round those, are generated and searched. Each one is reported with its state, what is under it, how much
     * water is over it and how far it is from spawn.
     */
    private static int geyserSurvey(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        BlockPos o = origin(level);
        int half = IntegerArgumentType.getInteger(context, "half");
        int x0 = o.getX() + IntegerArgumentType.getInteger(context, "dx") - half, z0 = o.getZ() + IntegerArgumentType.getInteger(context, "dz") - half;
        int x1 = x0 + 2 * half, z1 = z0 + 2 * half;
        long start = System.nanoTime();
        Set<Long> cave = new HashSet<>(), scan = new LinkedHashSet<>();
        int chunks = 0;
        for (int cx = x0 >> 4; cx <= x1 >> 4; cx++) {
            for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) {
                chunks++;
                outer:
                for (int y = -60; y <= 100; y += 8) {
                    for (int[] d : new int[][]{{4, 4}, {12, 12}, {4, 12}, {12, 4}}) {
                        if (Terrain.biome(level, (cx << 4) + d[0], y, (cz << 4) + d[1]).unwrapKey().map(k -> k.identifier().getPath().equals("sulfur_caves")).orElse(false)) {
                            cave.add(ChunkPos.pack(cx, cz));
                            break outer;
                        }
                    }
                }
            }
        }
        for (long c : cave) {
            ChunkPos p = ChunkPos.unpack(c);
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) scan.add(ChunkPos.pack(p.x() + dx, p.z() + dz));
        }
        long sampled = System.nanoTime();
        Map<String, Integer> byKind = new TreeMap<>();
        List<String> listed = new ArrayList<>();
        List<BlockPos> periodic = new ArrayList<>();
        int found = 0, inArea = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (long c : scan) {
            ChunkPos p = ChunkPos.unpack(c);
            var chunk = level.getChunk(p.x(), p.z());
            var sections = chunk.getSections();
            for (int i = 0; i < sections.length; i++) {
                var section = sections[i];
                if (section.hasOnlyAir() || !section.maybeHas(st -> st.is(Blocks.POTENT_SULFUR))) continue;
                int baseY = level.getMinY() + (i << 4);
                for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                    BlockState state = section.getBlockState(x, y, z);
                    if (!state.is(Blocks.POTENT_SULFUR)) continue;
                    pos.set((p.x() << 4) + x, baseY + y, (p.z() << 4) + z);
                    found++;
                    boolean inside = pos.getX() >= x0 && pos.getX() <= x1 && pos.getZ() >= z0 && pos.getZ() <= z1;
                    if (inside) inArea++;
                    BlockState below = level.getBlockState(pos.below());
                    int water = 0;
                    while (water < 8 && level.getFluidState(pos.above(water + 1)).isSourceOfType(Fluids.WATER)) water++;
                    String stateName = state.getValue(PotentSulfurBlock.STATE).getSerializedName();
                    String under = BuiltInRegistries.BLOCK.getKey(below.getBlock()).getPath();
                    int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE, pos.getX(), pos.getZ());
                    boolean open = surface <= pos.getY() + water + 2;
                    String biome = Terrain.biome(level, pos.getX(), pos.getY(), pos.getZ()).unwrapKey().map(k -> k.identifier().getPath()).orElse("?");
                    String kind = stateName + "/over " + under + "/" + (water > 0 ? "water" : "no water") + "/" + (open ? "open sky" : "covered");
                    byKind.merge(kind, 1, Integer::sum);
                    long distance = Math.round(Math.sqrt(Math.pow(pos.getX() - o.getX(), 2) + Math.pow(pos.getZ() - o.getZ(), 2)));
                    if (listed.size() < 14) listed.add(pos.toShortString() + " " + stateName + " over " + under + " water " + water + (open ? " open" : " covered") + " " + biome + " " + distance + "m");
                    if (below.is(Blocks.MAGMA_BLOCK) && water > 0) periodic.add(pos.immutable());
                    else if (stateName.equals("wet") && water > 0 && !WET.contains(pos)) WET.add(pos.immutable());
                }
            }
        }
        periodic.sort(Comparator.comparingDouble(b -> Math.pow(b.getX() - o.getX(), 2) + Math.pow(b.getZ() - o.getZ(), 2)));
        for (BlockPos b : periodic) if (!NATURAL.contains(b)) NATURAL.add(b);
        NATURAL.sort(Comparator.comparingDouble(b -> Math.pow(b.getX() - o.getX(), 2) + Math.pow(b.getZ() - o.getZ(), 2)));
        WET.sort(Comparator.comparingDouble(b -> Math.pow(b.getX() - o.getX(), 2) + Math.pow(b.getZ() - o.getZ(), 2)));
        double km2 = (2.0 * half) * (2.0 * half) / 1_000_000.0;
        report(source, String.format("geysers survey x %d..%d z %d..%d (%.2f km2): %d chunks, %d with sulfur caves below (biome sampled in %d ms), %d chunks generated and searched in %d ms; "
                + "potent sulfur %d (%d inside the area, %.1f per 1000x1000), periodic geysers (magma under, water over) %d; kinds %s; nearest periodic to spawn %s; first: %s",
            x0, x1, z0, z1, km2, chunks, cave.size(), (sampled - start) / 1_000_000, scan.size(), (System.nanoTime() - sampled) / 1_000_000,
            found, inArea, inArea / km2, periodic.size(), byKind, periodic.isEmpty() ? "none" : periodic.getFirst().toShortString() + " ("
                + Math.round(Math.sqrt(Math.pow(periodic.getFirst().getX() - o.getX(), 2) + Math.pow(periodic.getFirst().getZ() - o.getZ(), 2))) + "m)", listed));
        return 1;
    }

}
