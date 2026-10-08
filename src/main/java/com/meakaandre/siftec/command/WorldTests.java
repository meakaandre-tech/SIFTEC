package com.meakaandre.siftec.command;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.block.NodeBlock;
import com.meakaandre.siftec.engine.EngineBlockEntity;
import com.meakaandre.siftec.geyser.GeyserEngineBlockEntity;
import com.meakaandre.siftec.machine.ProcessorBlock;
import com.meakaandre.siftec.machine.ProcessorBlockEntity;
import com.meakaandre.siftec.machine.ProcessorRecipe;
import com.meakaandre.siftec.node.Node;
import com.meakaandre.siftec.node.NodeMap;
import com.meakaandre.siftec.node.NodePlacer;
import com.meakaandre.siftec.node.NodeSavedData;
import com.meakaandre.siftec.node.NodeType;
import com.meakaandre.siftec.node.Terrain;
import com.meakaandre.siftec.power.PoleBlockEntity;
import com.meakaandre.siftec.power.StorageBlockEntity;
import com.meakaandre.siftec.registry.ModBlocks;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PotentSulfurBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.PotentSulfurState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Selftests for how the mod fits the world: nodes, scanners, sulfur caves, geysers, Power Storage, Furnace Engines,
 * processors and Power Lines. Run by the automated test on an ordinary world. Every line of
 * output starts with "SELFTEST world".
 */
public final class WorldTests {
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
                .then(Commands.literal("furnace").then(Commands.literal("setup").executes(c -> furnace(c, true))).then(Commands.literal("check").executes(c -> furnace(c, false))))
                .then(Commands.literal("processor").executes(WorldTests::processor))
                .then(Commands.literal("powerline").then(Commands.literal("setup").executes(c -> powerline(c, true))).then(Commands.literal("check").executes(c -> powerline(c, false))))
                .then(Commands.literal("powerchain").then(Commands.literal("setup").executes(c -> powerchain(c, false))).then(Commands.literal("restart").executes(c -> powerchain(c, true))))
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
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> {
            if (watchedChunk != null && chunk.getPos().equals(watchedChunk)) watchedLoads++;
            CHAIN_LOADS.computeIfPresent(chunk.getPos().pack(), (k, v) -> v + 1);
            allLoads++;
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            MONITORS.clear();
            SITES.clear();
            NATURAL.clear();
            WET.clear();
            CHAIN_LOADS.clear();
            weakSource = null;
            watchedChunk = null;
        });
    }

    private static void report(CommandSourceStack source, String text) {
        String line = "SELFTEST world " + text;
        Siftec.LOGGER.info(line);
        source.sendSuccess(() -> Component.literal(line), false);
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

    private static final Map<String, BlockPos> SITES = new java.util.HashMap<>();

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
                var spot = com.meakaandre.siftec.collect.Collectibles.inCell(level, Math.floorDiv(o.getX(), com.meakaandre.siftec.collect.Collectibles.CELL) + dx,
                    Math.floorDiv(o.getZ(), com.meakaandre.siftec.collect.Collectibles.CELL) + dz);
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
                                if (!chunk.getFluidState(pos.above()).isSourceOfType(net.minecraft.world.level.material.Fluids.WATER)) {
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
        BlockPos o = level.dimension() == net.minecraft.world.level.Level.NETHER ? new BlockPos(150, 0, 150) : origin(level);
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
            if (state.getBlock() instanceof NodeBlock && state.getValue(NodeBlock.CORE)) break;
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
            result = "core at " + pos.toShortString() + " on " + BuiltInRegistries.BLOCK.getKey(under.getBlock()) + ", solid under the mound " + solid + "/" + around;
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
     * Watches a geyser with an engine on it for a while (a Power Storage on top of the engine) and reports: the
     * eruptions and the time between them, whether the engine ran outside an eruption, the SU-seconds of each
     * eruption, what the storage banked. {@code vent} null: builds a geyser beside spawn first.
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
        } else {
            // the chunk and the ones round it, so it is a fully ticking chunk and not only a loaded one
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) level.setChunkForced((vent.getX() >> 4) + dx, (vent.getZ() >> 4) + dz, true);
            level.getChunk(vent.getX() >> 4, vent.getZ() >> 4);
        }
        BlockPos forcedAt = vent;
        BlockState ventState = level.getBlockState(vent);
        if (!ventState.is(Blocks.POTENT_SULFUR)) {
            report(source, "geyser " + vent.toShortString() + ": no geyser there (" + ventState + ")");
            level.setChunkForced(vent.getX() >> 4, vent.getZ() >> 4, false);
            return 0;
        }
        int depth = 0;
        while (depth < 6 && level.getFluidState(vent.above(depth + 1)).isSourceOfType(net.minecraft.world.level.material.Fluids.WATER)) depth++;
        BlockPos onWater = vent.above(depth + 1), engine = vent.above(depth + 2), store = engine.above();
        boolean refused = GeyserEngineBlockEntity.blocksVent(level, onWater);
        level.setBlockAndUpdate(engine, ModBlocks.GEYSER_ENGINE.get().defaultBlockState());
        level.setBlockAndUpdate(store, ModBlocks.POWER_STORAGE.get().defaultBlockState());
        // the engine turns at 64 RPM: it belongs to a company whose speed limit allows that (unowned blocks break past 32)
        String company = fastCompany(source);
        for (BlockPos at : new BlockPos[]{engine, store}) {
            var be = level.getBlockEntity(at);
            if (be != null) be.setAttached(com.meakaandre.siftec.owner.Ownership.OWNER, company);
        }
        BlockPos finalVent = vent;
        report(source, "geyser " + vent.toShortString() + ": state " + ventState.getValue(PotentSulfurBlock.STATE).getSerializedName() + ", water " + depth
            + " deep; an engine right on the water would be refused: " + refused + "; engine found the vent: " + finalVent.equals(GeyserEngineBlockEntity.findVent(level, engine))
            + "; watching " + seconds + " s; engine block " + level.getBlockState(engine) + " entity " + level.getBlockEntity(engine)
            + (level.getBlockEntity(engine) instanceof GeyserEngineBlockEntity e ? " sees " + e.describe() : ""));
        MONITORS.add(new Monitor() {
            int ticks;
            long lastStart = -1;
            final List<Double> gaps = new ArrayList<>();
            final List<Integer> runs = new ArrayList<>();
            int eruptions, runningOutside, runningInside, current, inEruption;
            final List<String> seen = new ArrayList<>();
            boolean wasErupting;

            int waited;

            @Override
            public boolean tick() {
                // the watch starts once the geyser's chunk really ticks (up to a minute after the chunk is forced)
                if (ticks == 0 && !level.shouldTickBlocksAt(finalVent) && ++waited < 1200) return false;
                ticks++;
                BlockState state = level.getBlockState(finalVent);
                boolean erupting = state.is(Blocks.POTENT_SULFUR) && state.getValue(PotentSulfurBlock.STATE) == PotentSulfurState.ERUPTING;
                boolean running = level.getBlockEntity(engine) instanceof GeyserEngineBlockEntity e && e.running();
                if (erupting && !wasErupting) {
                    eruptions++;
                    if (lastStart >= 0) gaps.add((ticks - lastStart) / 20.0);
                    lastStart = ticks;
                }
                if (!erupting && wasErupting) {
                    runs.add(current);
                    current = 0;
                }
                if (erupting && wasErupting && ++inEruption == 10 && seen.size() < 3 && level.getBlockEntity(engine) instanceof GeyserEngineBlockEntity e) seen.add(e.describe());
                if (!erupting) inEruption = 0;
                if (running) {
                    current++;
                    if (erupting) runningInside++;
                    else runningOutside++;
                }
                wasErupting = erupting;
                if (ticks < seconds * 20) return false;
                String stored = level.getBlockEntity(store) instanceof StorageBlockEntity s ? String.format("%.0f SU-s, mode %d", s.stored, s.mode) : "missing";
                List<Double> su = new ArrayList<>();
                for (int run : runs) su.add(run / 20.0 * GeyserEngineBlockEntity.BURST_SU);
                double min = gaps.stream().mapToDouble(Double::doubleValue).min().orElse(0), max = gaps.stream().mapToDouble(Double::doubleValue).max().orElse(0);
                report(source, "geyser " + finalVent.toShortString() + " after " + seconds + " s (chunk ticking after " + waited / 20 + " s): " + eruptions + " eruptions, gaps " + gaps + " s (min " + min + ", max " + max
                    + ", all within 20-70: " + gaps.stream().allMatch(g -> g >= 20 && g <= 70) + "); engine ran " + runningInside + " ticks during eruptions and " + runningOutside
                    + " outside; SU-seconds per eruption " + su + "; storage on top banked " + stored + "; engine half a second into eruptions: " + seen + "; engine block now " + level.getBlockState(engine)
                    + (level.getBlockEntity(engine) instanceof GeyserEngineBlockEntity e ? " sees " + e.describe() : " (no engine entity)"));
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) level.setChunkForced((forcedAt.getX() >> 4) + dx, (forcedAt.getZ() >> 4) + dz, false);
                return true;
            }
        });
        return 1;
    }

    /** A company that has every speed limit milestone done, for machines that turn fast. */
    private static String fastCompany(CommandSourceStack source) {
        var data = com.meakaandre.siftec.company.CompanyData.get(source.getServer());
        com.meakaandre.siftec.company.Company company = data.byId("worldtest_fast");
        if (company == null) {
            company = new com.meakaandre.siftec.company.Company();
            company.id = "worldtest_fast";
            for (com.meakaandre.siftec.hub.Milestone m : com.meakaandre.siftec.hub.Milestones.all()) {
                if (m.tokens().stream().anyMatch(t -> t.startsWith("cap:"))) company.done.add(m.id());
            }
            data.companies().put(company.id, company);
        }
        return company.id;
    }

    // ---- Furnace Engines

    /**
     * Two furnaces. The first is placed showing flames but with nothing in it (as a blueprint used to build them):
     * its engine must give nothing. The second really burns, with three engines round it: only one may run on it.
     * Also checks that a blueprint now stores a furnace unlit.
     */
    private static int furnace(CommandContext<CommandSourceStack> context, boolean setup) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        BlockPos base = site(level, -24, -24);
        BlockPos fake = base, real = base.east(6);
        BlockPos[] engines = {real.north(), real.south(), real.west()};
        if (setup) {
            level.setChunkForced(base.getX() >> 4, base.getZ() >> 4, true);
            level.setChunkForced(real.getX() >> 4, real.getZ() >> 4, true);
            BlockState lit = Blocks.FURNACE.defaultBlockState().setValue(AbstractFurnaceBlock.LIT, true);
            report(source, "furnace: a blueprint keeps a burning furnace as lit=" + com.meakaandre.siftec.blueprint.Blueprints.tidy(lit).getValue(AbstractFurnaceBlock.LIT));
            level.setBlockAndUpdate(fake, lit);
            level.setBlockAndUpdate(fake.north(), ModBlocks.FURNACE_ENGINE.get().defaultBlockState());
            level.setBlockAndUpdate(real, Blocks.FURNACE.defaultBlockState());
            if (level.getBlockEntity(real) instanceof AbstractFurnaceBlockEntity furnace) {
                furnace.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
                furnace.setItem(1, new ItemStack(Items.COAL_BLOCK, 4));
            }
            for (BlockPos at : engines) level.setBlockAndUpdate(at, ModBlocks.FURNACE_ENGINE.get().defaultBlockState());
            return 1;
        }
        String fakeSu = level.getBlockEntity(fake.north()) instanceof EngineBlockEntity e ? e.providedSu() + " SU" : "missing";
        int runningEngines = 0;
        StringBuilder each = new StringBuilder();
        for (BlockPos at : engines) {
            if (level.getBlockEntity(at) instanceof EngineBlockEntity e) {
                each.append(" ").append(e.providedSu());
                if (e.providedSu() > 0) runningEngines++;
            }
        }
        report(source, "furnace: engine on a furnace that shows flames with no fuel gives " + fakeSu + " (furnace lit=" + level.getBlockState(fake).getValue(AbstractFurnaceBlock.LIT)
            + "); engines on one burning furnace:" + each + " -> " + runningEngines + " running");
        level.setChunkForced(base.getX() >> 4, base.getZ() >> 4, false);
        level.setChunkForced(real.getX() >> 4, real.getZ() >> 4, false);
        return 1;
    }

    // ---- processors

    /** A Converter or Particle Accelerator with a two-ingredient recipe, fed far too much of one ingredient: it must still take the other. */
    private static int processor(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        BlockPos at = site(level, -24, 24);
        for (ProcessorBlock block : List.of(ModBlocks.CONVERTER.get(), ModBlocks.PARTICLE_ACCELERATOR.get())) {
            List<ProcessorRecipe> recipes = ProcessorRecipe.of(block.machine);
            for (int index = 0; index < recipes.size(); index++) {
                ProcessorRecipe recipe = recipes.get(index);
                if (recipe.inputs().size() != 2 || recipe.fluidIn() != null || recipe.alt() != null || recipe.inputs().stream().anyMatch(c -> c.isTag() || !c.present())) continue;
                level.setBlockAndUpdate(at, block.defaultBlockState());
                if (!(level.getBlockEntity(at) instanceof ProcessorBlockEntity machine)) continue;
                machine.select(index);
                ItemStack a = new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(recipe.inputs().get(0).key())), 64);
                ItemStack b = new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(recipe.inputs().get(1).key())), 64);
                // like a hopper: the plentiful one first, into every slot that takes it
                int slotsA = 0, slotsB = 0;
                for (int slot = 0; slot < 9; slot++) {
                    if (machine.items.getItem(slot).isEmpty() && machine.items.canPlaceItem(slot, a)) {
                        machine.items.setItem(slot, a.copy());
                        slotsA++;
                    }
                }
                for (int slot = 0; slot < 9; slot++) {
                    if (machine.items.getItem(slot).isEmpty() && machine.items.canPlaceItem(slot, b)) {
                        machine.items.setItem(slot, b.copy());
                        slotsB++;
                    }
                }
                report(source, "processor " + block.machine + " recipe " + recipe.inputs() + ": plentiful ingredient took " + slotsA + " slots, the other " + slotsB
                    + "; ready to run: " + machine.readyNow());
                machine.items.clearContent();
                level.setBlockAndUpdate(at, Blocks.AIR.defaultBlockState());
                return 1;
            }
        }
        report(source, "processor: no two-ingredient recipe to test");
        return 0;
    }

    // ---- Power Lines

    private static ChunkPos watchedChunk;
    private static int watchedLoads, allLoads;

    /**
     * Setup: a Power Tower beside spawn and one 240 blocks away, linked; the far one's chunk is let go. Check: the
     * far chunk is unloaded; a creative motor under the near tower is started and stopped five times; the far
     * chunk must not be loaded by it.
     */
    private static int powerline(CommandContext<CommandSourceStack> context, boolean setup) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        BlockPos near = site(level, 24, 24);
        BlockPos far = new BlockPos(near.getX() + 240, near.getY(), near.getZ());
        if (setup) {
            level.setChunkForced(near.getX() >> 4, near.getZ() >> 4, true);
            level.setChunkForced(far.getX() >> 4, far.getZ() >> 4, true);
            level.getChunk(far.getX() >> 4, far.getZ() >> 4);
            level.setBlockAndUpdate(near, ModBlocks.POWER_TOWER.get().defaultBlockState());
            level.setBlockAndUpdate(far, ModBlocks.POWER_TOWER.get().defaultBlockState());
            level.setBlockAndUpdate(far.below(), ModBlocks.POWER_STORAGE.get().defaultBlockState());
            if (level.getBlockEntity(near) instanceof PoleBlockEntity a && level.getBlockEntity(far) instanceof PoleBlockEntity b) {
                a.link(far);
                b.link(near);
            }
            level.setChunkForced(far.getX() >> 4, far.getZ() >> 4, false);
            report(source, "powerline setup: towers at " + near.toShortString() + " and " + far.toShortString());
            return 1;
        }
        ChunkPos farChunk = new ChunkPos(far.getX() >> 4, far.getZ() >> 4);
        BlockState placed = BuiltInRegistries.BLOCK.getValue(Identifier.parse("create:creative_motor")).defaultBlockState();
        BlockState motor = placed.hasProperty(BlockStateProperties.FACING) ? placed.setValue(BlockStateProperties.FACING, Direction.UP) : placed;
        // a source under the near tower, on for a second and off for a second, five times (Create wires kinetics up on the next tick)
        MONITORS.add(new Monitor() {
            int ticks, waited, loadsBefore;
            float seen;

            @Override
            public boolean tick() {
                // first wait (up to a minute) until the far chunk has really been unloaded
                if (watchedChunk == null) {
                    if (level.hasChunk(farChunk.x(), farChunk.z()) && ++waited < 1200) return false;
                    watchedChunk = farChunk;
                    watchedLoads = 0;
                    loadsBefore = allLoads;
                }
                if (ticks % 20 == 0 && ticks < 200) level.setBlockAndUpdate(near.below(), ticks % 40 == 0 ? motor : Blocks.AIR.defaultBlockState());
                if (level.getBlockEntity(near) instanceof PoleBlockEntity p) seen = Math.max(seen, Math.abs(p.getSpeed()));
                if (++ticks < 220) return false;
                report(source, "powerline check: far chunk unloaded first " + (waited < 1200) + " (after " + waited / 20 + " s); five starts and stops of a source under the near tower (it turned at up to "
                    + seen + " RPM) loaded the far chunk " + watchedLoads + " times (chunk loads in all meanwhile: " + (allLoads - loadsBefore) + ")");
                watchedChunk = null;
                level.setChunkForced(near.getX() >> 4, near.getZ() >> 4, false);
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
        java.util.Set<Long> cave = new java.util.HashSet<>(), scan = new java.util.LinkedHashSet<>();
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
        Map<String, Integer> byKind = new java.util.TreeMap<>();
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
                    while (water < 8 && level.getFluidState(pos.above(water + 1)).isSourceOfType(net.minecraft.world.level.material.Fluids.WATER)) water++;
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
        periodic.sort(java.util.Comparator.comparingDouble(b -> Math.pow(b.getX() - o.getX(), 2) + Math.pow(b.getZ() - o.getZ(), 2)));
        for (BlockPos b : periodic) if (!NATURAL.contains(b)) NATURAL.add(b);
        NATURAL.sort(java.util.Comparator.comparingDouble(b -> Math.pow(b.getX() - o.getX(), 2) + Math.pow(b.getZ() - o.getZ(), 2)));
        WET.sort(java.util.Comparator.comparingDouble(b -> Math.pow(b.getX() - o.getX(), 2) + Math.pow(b.getZ() - o.getZ(), 2)));
        double km2 = (2.0 * half) * (2.0 * half) / 1_000_000.0;
        report(source, String.format("geysers survey x %d..%d z %d..%d (%.2f km2): %d chunks, %d with sulfur caves below (biome sampled in %d ms), %d chunks generated and searched in %d ms; "
                + "potent sulfur %d (%d inside the area, %.1f per 1000x1000), periodic geysers (magma under, water over) %d; kinds %s; nearest periodic to spawn %s; first: %s",
            x0, x1, z0, z1, km2, chunks, cave.size(), (sampled - start) / 1_000_000, scan.size(), (System.nanoTime() - sampled) / 1_000_000,
            found, inArea, inArea / km2, periodic.size(), byKind, periodic.isEmpty() ? "none" : periodic.getFirst().toShortString() + " ("
                + Math.round(Math.sqrt(Math.pow(periodic.getFirst().getX() - o.getX(), 2) + Math.pow(periodic.getFirst().getZ() - o.getZ(), 2))) + "m)", listed));
        return 1;
    }

    // ---- a long Power Line whose middle is unloaded

    /** Chunk loads of the chunks the chain test watches. */
    private static final Map<Long, Integer> CHAIN_LOADS = new java.util.concurrent.ConcurrentHashMap<>();
    /** Test only: the block entity at this position offers this much stress capacity per RPM instead of its own. */
    public static volatile BlockPos weakSource;
    public static volatile float weakCapacity;

    /**
     * Source, then five Power Towers 200 blocks apart (800 blocks), plus a branch from the middle tower: a creative
     * motor under the first, an encased fan under the last and under the branch's end. The first and the two ends
     * are kept loaded; the three in between are let go and must unload. Then: the far fans turn at the motor's RPM
     * in one network with its capacity; speed, direction, stop and overstress all reach them; and the middle
     * chunks are never loaded. After a restart: it works again with the middle still unloaded, and breaking the
     * middle tower (its chunk loaded for that, on purpose) cuts both ends.
     */
    private static BlockPos[] chain(ServerLevel level) {
        BlockPos o = origin(level);
        BlockPos t0 = new BlockPos(o.getX() + 64, 200, o.getZ() + 400);
        return new BlockPos[]{t0, t0.east(200), t0.east(400), t0.east(600), t0.east(800), t0.east(400).south(200)};
    }

    private static int powerchain(CommandContext<CommandSourceStack> context, boolean restart) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        BlockPos[] t = chain(level);
        BlockPos motorAt = t[0].below(), farFan = t[4].below(), branchFan = t[5].below();
        int[] middle = {1, 2, 3}, ends = {0, 4, 5};
        for (int i : middle) CHAIN_LOADS.put(ChunkPos.pack(t[i]), 0);
        if (!restart) {
            for (BlockPos p : t) level.setChunkForced(p.getX() >> 4, p.getZ() >> 4, true);
            for (BlockPos p : t) level.getChunk(p.getX() >> 4, p.getZ() >> 4);
            String company = fastCompany(source);
            BlockState motor = BuiltInRegistries.BLOCK.getValue(Identifier.parse("create:creative_motor")).defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP);
            BlockState fan = BuiltInRegistries.BLOCK.getValue(Identifier.parse("create:encased_fan")).defaultBlockState().setValue(BlockStateProperties.FACING, Direction.DOWN);
            for (BlockPos p : t) level.setBlockAndUpdate(p, ModBlocks.POWER_TOWER.get().defaultBlockState());
            level.setBlockAndUpdate(motorAt, motor);
            level.setBlockAndUpdate(farFan, fan);
            level.setBlockAndUpdate(branchFan, fan);
            for (BlockPos p : new BlockPos[]{t[0], t[1], t[2], t[3], t[4], t[5], motorAt, farFan, branchFan}) {
                var be = level.getBlockEntity(p);
                if (be != null) be.setAttached(com.meakaandre.siftec.owner.Ownership.OWNER, company);
            }
            int[][] links = {{0, 1}, {1, 2}, {2, 3}, {3, 4}, {2, 5}};
            for (int[] l : links) {
                if (level.getBlockEntity(t[l[0]]) instanceof PoleBlockEntity a && level.getBlockEntity(t[l[1]]) instanceof PoleBlockEntity b) {
                    a.link(t[l[1]]);
                    b.link(t[l[0]]);
                }
            }
            report(source, "powerchain setup: towers " + java.util.Arrays.stream(t).map(BlockPos::toShortString).toList() + " (a branch from the middle one); motor under the first, fans under the far end and the branch end");
        } else {
            for (int i : ends) level.setChunkForced(t[i].getX() >> 4, t[i].getZ() >> 4, true);
            report(source, "powerchain after restart: map of lines " + com.meakaandre.siftec.power.PowerGrid.get(level).describe(level) + "; middle chunks loaded " + middleLoaded(level, t));
        }
        MONITORS.add(new Monitor() {
            int ticks, step, waited, allBefore;
            boolean settled;

            String fans() {
                return "far fan " + speed(farFan) + " RPM" + (over(farFan) ? " OVERSTRESSED" : "") + ", branch fan " + speed(branchFan) + " RPM" + (over(branchFan) ? " OVERSTRESSED" : "");
            }

            float speed(BlockPos p) {
                return level.getBlockEntity(p) instanceof com.zurrtum.create.content.kinetics.base.KineticBlockEntity k ? k.getSpeed() : Float.NaN;
            }

            boolean over(BlockPos p) {
                return level.getBlockEntity(p) instanceof com.zurrtum.create.content.kinetics.base.KineticBlockEntity k && k.isOverStressed();
            }

            String network() {
                if (!(level.getBlockEntity(motorAt) instanceof com.zurrtum.create.content.kinetics.base.KineticBlockEntity m) || !(level.getBlockEntity(farFan) instanceof com.zurrtum.create.content.kinetics.base.KineticBlockEntity f)) return "no motor/fan";
                String far = f.hasNetwork() ? String.format("far fan's network capacity %.0f SU, stress %.0f SU", f.getOrCreateNetwork().calculateCapacity(), f.getOrCreateNetwork().calculateStress()) : "far fan has no network";
                String src = level.getBlockEntity(t[4]) instanceof PoleBlockEntity p4 && p4.source != null ? p4.source.toShortString() : "none";
                return "same network as the motor " + (m.network != null && m.network.equals(f.network)) + "; " + far + "; the motor offers " + m.calculateAddedStressCapacity() + " SU/RPM at " + m.getSpeed()
                    + " RPM; last tower takes its rotation from " + src;
            }

            void setMotor(int rpm) {
                if (level.getBlockEntity(motorAt) instanceof com.zurrtum.create.content.kinetics.motor.CreativeMotorBlockEntity m) m.generatedSpeed.setValue(rpm);
            }

            void capacity() {
                if (level.getBlockEntity(motorAt) instanceof com.zurrtum.create.content.kinetics.base.KineticBlockEntity m && m.hasNetwork()) m.getOrCreateNetwork().updateCapacityFor(m, m.calculateAddedStressCapacity());
            }

            @Override
            public boolean tick() {
                ticks++;
                if (!settled) {
                    // the chunks are new: count from when the motor's and the far fan's chunks really tick (up to a minute)
                    if (ticks == 1 && !(level.shouldTickBlocksAt(motorAt) && level.shouldTickBlocksAt(farFan) && level.shouldTickBlocksAt(branchFan)) && ++waited < 1200) {
                        ticks = 0;
                        return false;
                    }
                    if (!restart && ticks == 40) {
                        report(source, "powerchain all loaded: " + fans() + "; " + network());
                        for (int i : middle) level.setChunkForced(t[i].getX() >> 4, t[i].getZ() >> 4, false);
                    }
                    if (ticks < (restart ? 100 : 41)) return false;
                    if (middleAnyLoaded(level, t) && ++waited < 2400) return false;
                    settled = true;
                    for (int i : middle) CHAIN_LOADS.put(ChunkPos.pack(t[i]), 0);
                    allBefore = allLoads;
                    ticks = 0;
                    report(source, "powerchain middle unloaded " + !middleAnyLoaded(level, t) + " (after " + waited / 20 + " s): " + middleLoaded(level, t));
                    return false;
                }
                if (ticks % 30 != 0) return false;
                step++;
                String tag = "powerchain" + (restart ? " after restart" : "") + " step " + step + ": ";
                if (!restart) {
                    switch (step) {
                        case 1 -> {
                            report(source, tag + "motor at 16 RPM: " + fans() + "; " + network() + "; middle " + middleLoaded(level, t));
                            setMotor(24);
                        }
                        case 2 -> {
                            report(source, tag + "motor set to 24 RPM: " + fans());
                            setMotor(-16);
                        }
                        case 3 -> {
                            report(source, tag + "motor reversed to -16 RPM: " + fans());
                            setMotor(0);
                        }
                        case 4 -> {
                            report(source, tag + "motor stopped: " + fans());
                            setMotor(16);
                        }
                        case 5 -> {
                            report(source, tag + "motor started at 16 RPM again: " + fans() + "; " + network());
                            weakSource = motorAt;
                            weakCapacity = 1;
                            capacity();
                        }
                        case 6 -> {
                            report(source, tag + "motor weakened to 1 SU/RPM (two fans need 2 each): " + fans() + "; motor overstressed " + over(motorAt) + "; " + network());
                            weakSource = null;
                            capacity();
                        }
                        default -> {
                            report(source, tag + "motor back to full strength: " + fans() + "; middle " + middleLoaded(level, t) + "; loads of the middle chunks during all this " + CHAIN_LOADS
                                + " (chunk loads anywhere meanwhile, other tests included: " + (allLoads - allBefore) + "); map of lines " + com.meakaandre.siftec.power.PowerGrid.get(level).describe(level));
                            return true;
                        }
                    }
                } else {
                    switch (step) {
                        case 1 -> {
                            report(source, tag + "after the restart: " + fans() + "; " + network() + "; middle " + middleLoaded(level, t));
                            setMotor(24);
                        }
                        case 2 -> {
                            report(source, tag + "motor set to 24 RPM: " + fans() + "; middle loads so far " + CHAIN_LOADS);
                            // break the middle tower, the branch point: its chunk is loaded for that, on purpose, and let go again
                            level.setChunkForced(t[2].getX() >> 4, t[2].getZ() >> 4, true);
                            level.getChunk(t[2].getX() >> 4, t[2].getZ() >> 4);
                            level.destroyBlock(t[2], false);
                            level.setChunkForced(t[2].getX() >> 4, t[2].getZ() >> 4, false);
                        }
                        case 3 -> report(source, tag + "middle tower broken (its chunk loaded for it): " + fans() + "; loads of the middle chunks " + CHAIN_LOADS
                            + " (the broken tower's own chunk counts the one on purpose); map of lines " + com.meakaandre.siftec.power.PowerGrid.get(level).describe(level));
                        default -> {
                            report(source, tag + "a second later: " + fans() + "; middle " + middleLoaded(level, t) + "; chunk loads anywhere meanwhile: " + (allLoads - allBefore));
                            for (BlockPos p : t) level.setChunkForced(p.getX() >> 4, p.getZ() >> 4, false);
                            return true;
                        }
                    }
                }
                return false;
            }
        });
        return 1;
    }

    private static boolean middleAnyLoaded(ServerLevel level, BlockPos[] t) {
        for (int i = 1; i <= 3; i++) if (level.hasChunk(t[i].getX() >> 4, t[i].getZ() >> 4)) return true;
        return false;
    }

    private static String middleLoaded(ServerLevel level, BlockPos[] t) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 1; i <= 3; i++) out.append(i > 1 ? ", " : "").append("tower ").append(i).append(' ').append(level.hasChunk(t[i].getX() >> 4, t[i].getZ() >> 4) ? "loaded" : "unloaded");
        return out.append(']').toString();
    }
}
