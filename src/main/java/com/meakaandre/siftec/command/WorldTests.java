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
 * processors and Power Lines. Run by the automated test on an ordinary world and on The Isles. Every line of
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
                .then(Commands.literal("pockets").then(Commands.argument("list", StringArgumentType.greedyString()).executes(WorldTests::pockets)))
                .then(Commands.literal("place").then(Commands.argument("type", StringArgumentType.word()).executes(WorldTests::place)))
                .then(Commands.literal("geyser").then(Commands.literal("build").then(seconds.executes(c -> geyser(c, null)))).then(geyserAt))
                .then(Commands.literal("furnace").then(Commands.literal("setup").executes(c -> furnace(c, true))).then(Commands.literal("check").executes(c -> furnace(c, false))))
                .then(Commands.literal("processor").executes(WorldTests::processor))
                .then(Commands.literal("powerline").then(Commands.literal("setup").executes(c -> powerline(c, true))).then(Commands.literal("check").executes(c -> powerline(c, false))));
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
            allLoads++;
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            MONITORS.clear();
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

    /** "x,z;x,z;..." of the sulfur cave pockets the world is known to have: how many hold a sulfur node. */
    private static int pockets(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        int found = 0, total = 0;
        List<String> missing = new ArrayList<>();
        long start = System.nanoTime();
        for (String part : StringArgumentType.getString(context, "list").split(";")) {
            String[] xz = part.trim().split(",");
            if (xz.length != 2) continue;
            int x = Integer.parseInt(xz[0].trim()), z = Integer.parseInt(xz[1].trim());
            total++;
            boolean hit = false;
            for (int dx = -1; dx <= 1 && !hit; dx++) {
                for (int dz = -1; dz <= 1 && !hit; dz++) {
                    for (Node node : NodeMap.inCell(level, Math.floorDiv(x, NodeMap.CELL) + dx, Math.floorDiv(z, NodeMap.CELL) + dz)) {
                        if (node.type() == NodeType.SULFUR && node.distanceTo(x, z) <= 48) hit = true;
                    }
                }
            }
            if (hit) found++;
            else missing.add(x + "," + z);
        }
        report(source, "sulfur pockets with a sulfur node: " + found + " of " + total + " (" + (System.nanoTime() - start) / 1_000_000 + " ms); missing " + missing);
        return 1;
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
            level.setChunkForced(vent.getX() >> 4, vent.getZ() >> 4, true);
            level.getChunk(vent.getX() >> 4, vent.getZ() >> 4);
        }
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
        BlockPos finalVent = vent;
        report(source, "geyser " + vent.toShortString() + ": state " + ventState.getValue(PotentSulfurBlock.STATE).getSerializedName() + ", water " + depth
            + " deep; an engine right on the water would be refused: " + refused + "; engine found the vent: " + finalVent.equals(GeyserEngineBlockEntity.findVent(level, engine))
            + "; watching " + seconds + " s");
        MONITORS.add(new Monitor() {
            int ticks;
            long lastStart = -1;
            final List<Double> gaps = new ArrayList<>();
            final List<Integer> runs = new ArrayList<>();
            int eruptions, runningOutside, runningInside, current;
            boolean wasErupting;

            @Override
            public boolean tick() {
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
                report(source, "geyser " + finalVent.toShortString() + " after " + seconds + " s: " + eruptions + " eruptions, gaps " + gaps + " s (min " + min + ", max " + max
                    + ", all within 20-70: " + gaps.stream().allMatch(g -> g >= 20 && g <= 70) + "); engine ran " + runningInside + " ticks during eruptions and " + runningOutside
                    + " outside; SU-seconds per eruption " + su + "; storage on top banked " + stored);
                level.setChunkForced(finalVent.getX() >> 4, finalVent.getZ() >> 4, false);
                return true;
            }
        });
        return 1;
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
        boolean loadedBefore = level.hasChunk(far.getX() >> 4, far.getZ() >> 4);
        watchedChunk = new ChunkPos(far.getX() >> 4, far.getZ() >> 4);
        watchedLoads = 0;
        int loadsBefore = allLoads;
        BlockState placed = BuiltInRegistries.BLOCK.getValue(Identifier.parse("create:creative_motor")).defaultBlockState();
        BlockState motor = placed.hasProperty(BlockStateProperties.FACING) ? placed.setValue(BlockStateProperties.FACING, Direction.UP) : placed;
        // a source under the near tower, on for a second and off for a second, five times (Create wires kinetics up on the next tick)
        MONITORS.add(new Monitor() {
            int ticks;
            float seen;

            @Override
            public boolean tick() {
                if (ticks % 20 == 0 && ticks < 200) level.setBlockAndUpdate(near.below(), ticks % 40 == 0 ? motor : Blocks.AIR.defaultBlockState());
                if (level.getBlockEntity(near) instanceof PoleBlockEntity p) seen = Math.max(seen, Math.abs(p.getSpeed()));
                if (++ticks < 220) return false;
                report(source, "powerline check: far chunk loaded before " + loadedBefore + "; five starts and stops of a source under the near tower (it turned at up to "
                    + seen + " RPM) loaded the far chunk " + watchedLoads + " times (chunk loads in all meanwhile: " + (allLoads - loadsBefore) + ")");
                watchedChunk = null;
                level.setChunkForced(near.getX() >> 4, near.getZ() >> 4, false);
                return true;
            }
        });
        return 1;
    }
}
