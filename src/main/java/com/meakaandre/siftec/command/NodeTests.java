package com.meakaandre.siftec.command;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.block.MinerBlock;
import com.meakaandre.siftec.block.MinerBlockEntity;
import com.meakaandre.siftec.block.MinerTier;
import com.meakaandre.siftec.block.NodeBlock;
import com.meakaandre.siftec.node.Node;
import com.meakaandre.siftec.node.NodeMap;
import com.meakaandre.siftec.node.NodePlacer;
import com.meakaandre.siftec.node.NodeSavedData;
import com.meakaandre.siftec.node.NodeType;
import com.meakaandre.siftec.node.Purity;
import com.meakaandre.siftec.owner.Ownership;
import com.meakaandre.siftec.registry.ModBlocks;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.zurrtum.create.content.kinetics.base.BlockBreakingKineticBlockEntity;
import com.zurrtum.create.content.kinetics.motor.CreativeMotorBlockEntity;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.PushReaction;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Selftests and camera spots for node pads and miners, run by the automated test on an ordinary world. Every line
 * of output starts with "SELFTEST world".
 * <ul>
 * <li>{@code worldtest pads <n>}: places the n nodes nearest spawn and reports, for each, whether its pad is level,
 * fully supported and open above; then tries to break a pad block and the node the way a survival player would,
 * blows a charge on the pad, and asks Create's drills, pistons and contraptions whether they could take it.</li>
 * <li>{@code worldtest miners setup}: on a real node of each purity runs Mk.1, Mk.2 and Mk.3 in turn at 64 RPM and
 * reports cycle time, items and fluid per cycle and stress; then leaves Mk.1 / Mk.2 / Mk.3 spinning on the impure /
 * normal / pure node. {@code freeze} stops them and reports their tank and progress, {@code verify} (after a
 * restart) reports them again.</li>
 * <li>{@code worldtest showroom}: a row of pads in the sky with every node type at each purity, and the three miner
 * marks spinning on pads; {@code worldtest look ...} puts every player at a camera spot for screenshots.</li>
 * </ul>
 */
public final class NodeTests {
    private NodeTests() {
    }

    private static final List<WorldTestsMonitor> MONITORS = new ArrayList<>();
    /** The miners the miner test left on nodes, by purity. */
    private static final Map<Purity, BlockPos> MINERS = new EnumMap<>(Purity.class);
    private static final int SHOW_Y = 200, SHOW_DZ = 48;

    private interface WorldTestsMonitor {
        boolean tick();
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            var angle = Commands.argument("angle", IntegerArgumentType.integer(0, 8));
            var worldtest = Commands.literal("worldtest")
                .then(Commands.literal("pads").then(Commands.argument("count", IntegerArgumentType.integer(1, 40)).executes(NodeTests::pads)))
                .then(Commands.literal("miners")
                    .then(Commands.literal("setup").executes(NodeTests::minersSetup))
                    .then(Commands.literal("freeze").executes(c -> minersReport(c, true)))
                    .then(Commands.literal("verify").executes(c -> minersReport(c, false))))
                .then(Commands.literal("showroom").executes(NodeTests::showroom))
                .then(Commands.literal("look")
                    .then(Commands.literal("showroom").then(Commands.argument("col", IntegerArgumentType.integer(0, 20))
                        .then(Commands.argument("row", IntegerArgumentType.integer(0, 3)).then(angle.executes(c -> lookShowroom(c, 0))
                            .then(Commands.argument("dist", IntegerArgumentType.integer(2, 80)).executes(c -> lookShowroom(c, IntegerArgumentType.getInteger(c, "dist"))))))))
                    .then(Commands.literal("node").then(Commands.argument("type", StringArgumentType.word())
                        .then(Commands.argument("angle", IntegerArgumentType.integer(0, 8)).executes(NodeTests::lookNode))))
                    .then(Commands.literal("miner").then(Commands.argument("index", IntegerArgumentType.integer(0, 2))
                        .then(Commands.argument("angle", IntegerArgumentType.integer(0, 8)).executes(NodeTests::lookMiner)))));
            dispatcher.register(Commands.literal("siftec").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).then(worldtest));
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> MONITORS.removeIf(WorldTestsMonitor::tick));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            MONITORS.clear();
            MINERS.clear();
        });
    }

    private static void report(CommandSourceStack source, String text) {
        String line = "SELFTEST world " + text;
        Siftec.LOGGER.info(line);
        source.sendSuccess(() -> Component.literal(line), false);
    }

    private static BlockPos origin() {
        int[] o = NodeMap.origin();
        return o == null ? BlockPos.ZERO : new BlockPos(o[0], 0, o[1]);
    }

    /** Loads (and keeps loaded while {@code keep}) the chunks round a node and places it. */
    private static void place(ServerLevel level, Node node, boolean keep) {
        int cx = node.x() >> 4, cz = node.z() >> 4;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            level.setChunkForced(cx + dx, cz + dz, true);
            level.getChunk(cx + dx, cz + dz);
        }
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) NodePlacer.placeChunk(level, cx + dx, cz + dz);
        if (!keep) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) level.setChunkForced(cx + dx, cz + dz, false);
    }

    /** The node's core, or null if it has none placed. */
    private static BlockPos core(ServerLevel level, Node node) {
        int y = NodeSavedData.get(level.getServer()).height(node.key());
        if (y == NodeSavedData.NO_HEIGHT || y == NodePlacer.NO_PLACE) return null;
        BlockPos pos = new BlockPos(node.x(), y + 1, node.z());
        BlockState state = level.getBlockState(pos);
        return state.getBlock() instanceof NodeBlock && state.getValue(NodeBlock.CORE) ? pos : null;
    }

    /** The nodes nearest spawn, cell ring by cell ring, that pass the filter. */
    private static List<Node> nearest(ServerLevel level, int count, java.util.function.Predicate<Node> filter) {
        BlockPos o = origin();
        int cx0 = Math.floorDiv(o.getX(), NodeMap.CELL), cz0 = Math.floorDiv(o.getZ(), NodeMap.CELL);
        List<Node> found = new ArrayList<>();
        for (int ring = 0; ring <= 8 && found.size() < count; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    for (Node node : NodeMap.inCell(level, cx0 + dx, cz0 + dz)) {
                        if (found.size() < count && filter.test(node)) found.add(node);
                    }
                }
            }
        }
        return found;
    }

    // ---- pads

    /** Level, supported, open above: one line about a placed node's pad. */
    public static String padReport(ServerLevel level, Node node) {
        int y = NodeSavedData.get(level.getServer()).height(node.key());
        if (y == NodeSavedData.NO_HEIGHT || y == NodePlacer.NO_PLACE) return "no pad (height " + (y == NodePlacer.NO_PLACE ? "none fit" : "not chosen") + ")";
        if (!NodeSavedData.get(level.getServer()).isFlat(node.key())) return "an old-style mound (placed before pads)";
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int cells = 0, level0 = 0, offLevel = 0, unsupported = 0, blocked = 0, fill = 0;
        List<String> odd = new ArrayList<>();
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                int x = node.x() + dx, z = node.z() + dz;
                if (dx * dx + dz * dz > NodePlacer.PAD_RADIUS * NodePlacer.PAD_RADIUS) continue;
                cells++;
                BlockState at = level.getBlockState(pos.set(x, y, z));
                if (at.is(ModBlocks.NODE_PAD.get())) level0++;
                else if (odd.size() < 4) odd.add(dx + "," + dz + "=" + BuiltInRegistries.BLOCK.getKey(at.getBlock()).getPath());
                for (int d = -4; d <= 4; d++) if (d != 0 && level.getBlockState(pos.set(x, y + d, z)).is(ModBlocks.NODE_PAD.get())) offLevel++;
                BlockState under = level.getBlockState(pos.set(x, y - 1, z));
                if (under.isAir() || !under.getFluidState().isEmpty()) unsupported++;
                for (int d = 1; level.getBlockState(pos.set(x, y - d, z)).is(ModBlocks.NODE_PAD_FILL.get()) && d < 64; d++) fill++;
                for (int up = 1; up <= 4; up++) {
                    if (dx == 0 && dz == 0 && up == 1) continue;
                    BlockState above = level.getBlockState(pos.set(x, y + up, z));
                    if (!above.isAir() && !above.is(Blocks.SNOW) && above.getFluidState().isEmpty()) blocked++;
                }
            }
        }
        BlockState core = level.getBlockState(new BlockPos(node.x(), y + 1, node.z()));
        boolean isCore = core.getBlock() instanceof NodeBlock && core.getValue(NodeBlock.CORE);
        return "pad at y " + y + ": " + level0 + "/" + cells + " pad blocks level" + (odd.isEmpty() ? "" : " (others " + odd + ")") + ", " + offLevel
            + " pad blocks off that level, " + unsupported + " with air or fluid under, " + fill + " fill blocks under it, " + blocked
            + " solid blocks in the 4 above it; node " + (isCore ? BuiltInRegistries.BLOCK.getKey(core.getBlock()).getPath() + " purity "
            + core.getValue(NodeBlock.PURITY).getSerializedName() + " (map says " + node.purity().getSerializedName() + ")" : "MISSING (" + core + ")");
    }

    private static int pads(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        NodeMap.settle(level);
        int count = IntegerArgumentType.getInteger(context, "count");
        List<Node> nodes = nearest(level, count, n -> true);
        Node first = null;
        long start = System.nanoTime();
        for (Node node : nodes) {
            place(level, node, false);
            report(source, "pad " + node.type().id() + " " + node.purity().getSerializedName() + " at " + node.x() + "," + node.z() + ": " + padReport(level, node));
            // the blast test keeps off the iron and copper nodes the other selftests put machines on
            if (first == null && node.y() == Node.SURFACE && node.type() != NodeType.IRON && node.type() != NodeType.COPPER && core(level, node) != null) first = node;
        }
        report(source, "pads: " + nodes.size() + " nodes placed in " + (System.nanoTime() - start) / 1_000_000 + " ms");
        if (first != null) breakTest(source, level, first);
        return 1;
    }

    /** Tries everything that could take a pad block or the node away, and reports what is left. */
    private static void breakTest(CommandSourceStack source, ServerLevel level, Node node) {
        BlockPos core = core(level, node);
        int y = core.getY() - 1;
        BlockPos edge = new BlockPos(node.x() + 3, y, node.z());
        place(level, node, true);
        BlockState pad = level.getBlockState(edge);
        TagKey<Block> nonMovable = TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("create", "non_movable"));
        StringBuilder out = new StringBuilder("unbreakable " + BuiltInRegistries.BLOCK.getKey(pad.getBlock()) + " at " + edge.toShortString() + ": hardness "
            + pad.getDestroySpeed(level, edge) + ", blast resistance " + pad.getBlock().getExplosionResistance() + ", piston "
            + pad.getPistonPushReaction() + (pad.getPistonPushReaction() == PushReaction.IMMOVEABLE ? " (immovable)" : "") + ", create:non_movable "
            + pad.is(nonMovable) + ", node in create:non_movable " + level.getBlockState(core).is(nonMovable) + ", Create drill could break pad "
            + BlockBreakingKineticBlockEntity.isBreakable(pad, pad.getDestroySpeed(level, edge)) + " / node "
            + BlockBreakingKineticBlockEntity.isBreakable(level.getBlockState(core), level.getBlockState(core).getDestroySpeed(level, core)));
        // a survival player digging: start and finish breaking, as the client would
        var player = net.fabricmc.fabric.api.entity.FakePlayer.get(level);
        player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        for (BlockPos target : new BlockPos[]{edge, core, edge.below()}) {
            player.setPos(target.getX() + 0.5, target.getY() + 1, target.getZ() - 1.5);
            BlockState before = level.getBlockState(target);
            float progress = before.getDestroyProgress(player, level, target);
            player.gameMode.handleBlockBreakAction(target, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, Direction.UP, level.getMaxY(), 0);
            player.gameMode.handleBlockBreakAction(target, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, Direction.UP, level.getMaxY(), 1);
            out.append("; survival player on ").append(BuiltInRegistries.BLOCK.getKey(before.getBlock()).getPath()).append(": progress per tick ").append(progress)
                .append(", still there ").append(level.getBlockState(target).is(before.getBlock()));
        }
        // a big charge on the pad
        int padBefore = countPad(level, node, y);
        level.explode(null, edge.getX() + 0.5, y + 1.5, edge.getZ() + 0.5, 6f, Level.ExplosionInteraction.TNT);
        level.explode(null, node.x() + 0.5, y + 2.5, node.z() + 0.5, 6f, Level.ExplosionInteraction.TNT);
        out.append("; two power-6 explosions: pad blocks ").append(padBefore).append(" -> ").append(countPad(level, node, y))
            .append(", node still there ").append(core(level, node) != null);
        report(source, out.toString());
        int cx = node.x() >> 4, cz = node.z() >> 4;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) level.setChunkForced(cx + dx, cz + dz, false);
    }

    private static int countPad(ServerLevel level, Node node, int y) {
        int n = 0;
        for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) {
            if (level.getBlockState(new BlockPos(node.x() + dx, y, node.z() + dz)).is(ModBlocks.NODE_PAD.get())) n++;
        }
        return n;
    }

    // ---- miners

    private static final int WARM = 20, RUN = 200, SPEED = 64;

    /**
     * Where the miner test may build: past the 12 nearest nodes (the pad test blows a charge on one of those), never
     * iron or copper (the other selftests put machines on the nearest of those), only nodes a miner can work.
     * The same nodes every time, so a restart finds them again.
     */
    private static List<Node> minerNodes(ServerLevel level) {
        List<Node> all = nearest(level, 72, n -> true);
        return all.subList(Math.min(12, all.size()), all.size()).stream().filter(n -> !n.type().isFluid() && n.y() == Node.SURFACE
            && n.type() != NodeType.IRON && n.type() != NodeType.COPPER && n.type().output() != net.minecraft.world.item.Items.AIR).toList();
    }

    private static Block minerBlock(MinerTier tier) {
        return switch (tier) {
            case MK1 -> ModBlocks.MINER_MK1.get();
            case MK2 -> ModBlocks.MINER_MK2.get();
            case MK3 -> ModBlocks.MINER_MK3.get();
        };
    }

    /** A miner of this mark at {@code at}, its upper block, a creative motor at 64 RPM on top, and 1,000 mB of its fluid. */
    private static MinerBlockEntity build(ServerLevel level, BlockPos at, MinerTier tier, String company) {
        for (int up = 2; up >= 0; up--) level.setBlock(at.above(up), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlockAndUpdate(at, minerBlock(tier).defaultBlockState());
        MinerBlock.placeTop(level, at);
        BlockState motor = BuiltInRegistries.BLOCK.getValue(Identifier.parse("create:creative_motor")).defaultBlockState();
        if (motor.hasProperty(BlockStateProperties.FACING)) motor = motor.setValue(BlockStateProperties.FACING, Direction.DOWN);
        level.setBlockAndUpdate(at.above(2), motor);
        // owned by a company allowed 64 RPM (unowned blocks break past 32)
        for (int up = 0; up <= 2; up++) {
            var be = level.getBlockEntity(at.above(up));
            if (be != null) be.setAttached(Ownership.OWNER, company);
        }
        if (level.getBlockEntity(at.above(2)) instanceof CreativeMotorBlockEntity m) m.generatedSpeed.setValue(SPEED);
        if (!(level.getBlockEntity(at) instanceof MinerBlockEntity miner)) return null;
        miner.fill(tier.fluid(), 1000);
        return miner;
    }

    private static int minersSetup(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        NodeMap.settle(level);
        String company = WorldTests.fastCompany(source);
        Map<Purity, Node> picked = new EnumMap<>(Purity.class);
        for (Node node : minerNodes(level)) picked.putIfAbsent(node.purity(), node);
        report(source, "miners: nodes " + picked);
        for (Map.Entry<Purity, Node> entry : picked.entrySet()) {
            Node node = entry.getValue();
            place(level, node, true);
            BlockPos core = core(level, node);
            if (core == null) {
                report(source, "miners: " + node + " has no core: " + padReport(level, node));
                continue;
            }
            BlockPos at = core.above();
            MINERS.put(entry.getKey(), at);
            MONITORS.add(new WorldTestsMonitor() {
                int tier, t, c0, items0, f0;
                MinerBlockEntity miner;

                @Override
                public boolean tick() {
                    MinerTier mark = MinerTier.values()[Math.max(tier, 0)];
                    if (t == 0 && tier >= 0) miner = build(level, at, mark, company);
                    t++;
                    if (miner == null || miner.isRemoved()) {
                        report(source, "miner " + mark + " on " + node + ": no miner block entity (" + level.getBlockState(at) + ")");
                        return true;
                    }
                    if (tier < 0) {
                        if (t < 70) return false;
                        level.setBlockAndUpdate(at.above(2), Blocks.AIR.defaultBlockState());
                        report(source, "miner freeze " + node.purity().getSerializedName() + " at " + at.toShortString() + ": "
                            + miner.tier() + " fluid " + miner.fluidAmount() + " mB, progress " + miner.progress() + ", output " + miner.output.get());
                        return true;
                    }
                    if (t == WARM) {
                        c0 = miner.cycles;
                        items0 = miner.output.get().getCount();
                        f0 = miner.fluidAmount();
                    }
                    if (t == WARM + RUN) {
                        int cycles = miner.cycles - c0, items = miner.output.get().getCount() - items0, fluid = f0 - miner.fluidAmount();
                        float speed = Math.abs(miner.getSpeed()), perRpm = miner.calculateStressApplied();
                        report(source, String.format(Locale.ROOT,
                            "miner %s on %s %s at %s: %.0f RPM, %d cycles in %d ticks (%s ticks a cycle), %d %s (%.2f a cycle; expected %.1f, fractions carry over), %d mB %s used (%s a cycle), stress %.0f SU per RPM = %.0f SU, status %s, overstressed %s",
                            mark, node.purity().getSerializedName(), node.type().id(), at.toShortString(), speed, cycles, RUN,
                            cycles == 0 ? "-" : String.format(Locale.ROOT, "%.1f", RUN / (float) cycles), items,
                            BuiltInRegistries.ITEM.getKey(node.type().output()).getPath(), cycles == 0 ? 0f : items / (float) cycles,
                            node.purity().multiplier * mark.output, fluid, mark.fluidName, cycles == 0 ? "-" : String.format(Locale.ROOT, "%.1f", fluid / (float) cycles),
                            perRpm, perRpm * speed, miner.status(), miner.isOverStressed()));
                        miner.output.clearContent();
                        t = 0;
                        tier++;
                        if (tier == MinerTier.values().length) {
                            // left for the screenshots and the restart check: Mk.1 on the impure node, Mk.2 on the normal one, Mk.3 on the pure one,
                            // run for 70 ticks (part way into a cycle), then stopped and reported, to be compared after the restart
                            miner = build(level, at, MinerTier.values()[node.purity().ordinal()], company);
                            tier = -1;
                        }
                    }
                    return false;
                }
            });
        }
        return 1;
    }

    /** freeze: stops the miners the test left (their motors go) and reports them; verify: reports them after a restart. */
    private static int minersReport(CommandContext<CommandSourceStack> context, boolean freeze) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        NodeMap.settle(level);
        if (MINERS.isEmpty()) {
            // after a restart: find the same nodes again (the search is the same, so are the nodes)
            Map<Purity, Node> picked = new EnumMap<>(Purity.class);
            for (Node node : minerNodes(level)) picked.putIfAbsent(node.purity(), node);
            for (Map.Entry<Purity, Node> entry : picked.entrySet()) {
                place(level, entry.getValue(), false);
                BlockPos core = core(level, entry.getValue());
                if (core != null) MINERS.put(entry.getKey(), core.above());
            }
        }
        for (Map.Entry<Purity, BlockPos> entry : MINERS.entrySet()) {
            BlockPos at = entry.getValue();
            level.getChunk(at);
            if (freeze) level.setBlockAndUpdate(at.above(2), Blocks.AIR.defaultBlockState());
            report(source, "miner " + (freeze ? "freeze" : "after restart") + " " + entry.getKey().getSerializedName() + " at " + at.toShortString() + ": "
                + (level.getBlockEntity(at) instanceof MinerBlockEntity m
                ? m.tier() + " fluid " + m.fluidAmount() + " mB, progress " + m.progress() + ", output " + m.output.get() + ", upper block " + BuiltInRegistries.BLOCK.getKey(level.getBlockState(at.above()).getBlock())
                : "no miner (" + level.getBlockState(at) + ")"));
        }
        return 1;
    }

    // ---- the showroom and camera spots

    private static BlockPos showCentre(int col, int row) {
        BlockPos o = origin();
        return new BlockPos(o.getX() - 50 + col * 10, SHOW_Y, o.getZ() + SHOW_DZ + row * 10);
    }

    private static int showroom(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        NodeMap.settle(level);
        String company = WorldTests.fastCompany(source);
        NodeType[] types = NodeType.values();
        for (int col = 0; col < types.length; col++) {
            for (int row = 0; row < 4; row++) {
                BlockPos c = showCentre(col, row);
                if (row == 3 && col >= MinerTier.values().length) continue;
                level.getChunk(c);
                for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) {
                    if (dx * dx + dz * dz <= NodePlacer.PAD_RADIUS * NodePlacer.PAD_RADIUS) {
                        level.setBlockAndUpdate(c.offset(dx, 0, dz), ModBlocks.NODE_PAD.get().defaultBlockState());
                    }
                }
                Purity purity = row < 3 ? Purity.values()[row] : Purity.PURE;
                NodeType type = row < 3 ? types[col] : NodeType.IRON;
                level.setBlockAndUpdate(c.above(), NodePlacer.coreState(new Node(c.getX(), c.getZ(), type, purity)));
                // the bottom row: the three miner marks, spinning, on pure iron
                if (row == 3) build(level, c.above(2), MinerTier.values()[col], company);
            }
        }
        report(source, "showroom: " + types.length + " node types x 3 purities and 3 miners at y " + SHOW_Y + " from " + showCentre(0, 0).toShortString());
        return 1;
    }

    /** Puts every player at a camera spot looking at {@code target}: angle 0-7 round it, 8 straight down. */
    private static void look(CommandSourceStack source, ServerLevel level, double tx, double ty, double tz, int angle, double distance, double height) {
        double cx, cy, cz;
        if (angle == 8) {
            cx = tx;
            cy = ty + distance + 1;
            cz = tz + 0.01;
        } else {
            double a = angle * Math.PI / 4;
            cx = tx + Math.sin(a) * distance;
            cz = tz - Math.cos(a) * distance;
            cy = ty + height;
        }
        double dx = tx - cx, dy = ty - (cy + 1.62), dz = tz - cz;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        String dim = level.dimension().identifier().toString();
        source.getServer().getCommands().performPrefixedCommand(source.getServer().createCommandSourceStack().withSuppressedOutput(),
            String.format(Locale.ROOT, "execute in %s run tp @a %.2f %.2f %.2f %.1f %.1f", dim, cx, cy, cz, yaw, pitch));
        report(source, String.format(Locale.ROOT, "look at %.1f %.1f %.1f in %s from %.1f %.1f %.1f", tx, ty, tz, dim, cx, cy, cz));
    }

    private static int lookShowroom(CommandContext<CommandSourceStack> context, int dist) {
        int col = IntegerArgumentType.getInteger(context, "col"), row = IntegerArgumentType.getInteger(context, "row");
        BlockPos c = showCentre(col, row);
        boolean miner = row == 3;
        double distance = dist > 0 ? dist : miner ? 5.5 : 4.0;
        look(context.getSource(), context.getSource().getServer().overworld(), c.getX() + 0.5, c.getY() + (miner ? 2.5 : 1.5), c.getZ() + 0.5,
            IntegerArgumentType.getInteger(context, "angle"), distance, dist > 0 ? distance * 0.5 : miner ? 0.5 : 0.8);
        return 1;
    }

    private static int lookNode(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        NodeType type = NodeType.byId(StringArgumentType.getString(context, "type"));
        if (type == null) return 0;
        ServerLevel level = source.getServer().getLevel(type.dimension());
        if (level == null) return 0;
        NodeMap.settle(source.getServer().overworld());
        BlockPos o = type == NodeType.QUARTZ ? new BlockPos(150, 0, 150) : origin();
        Optional<Node> found = NodeMap.nearest(level, o.getX(), o.getZ(), type, type == NodeType.SULFUR ? 24 : 32);
        if (found.isEmpty()) {
            report(source, "look node " + type.id() + ": none");
            return 0;
        }
        place(level, found.get(), false);
        BlockPos core = core(level, found.get());
        if (core == null) {
            report(source, "look node " + type.id() + ": " + padReport(level, found.get()));
            return 0;
        }
        boolean cave = type == NodeType.SULFUR;
        look(source, level, core.getX() + 0.5, core.getY() + 0.5, core.getZ() + 0.5, IntegerArgumentType.getInteger(context, "angle"), cave ? 4.5 : 7.0, cave ? 1.0 : 3.0);
        report(source, "look node " + type.id() + " " + found.get() + ": " + padReport(level, found.get()));
        return 1;
    }

    private static int lookMiner(CommandContext<CommandSourceStack> context) {
        BlockPos at = MINERS.get(Purity.values()[IntegerArgumentType.getInteger(context, "index")]);
        if (at == null) {
            report(context.getSource(), "look miner: none set up");
            return 0;
        }
        look(context.getSource(), context.getSource().getServer().overworld(), at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5,
            IntegerArgumentType.getInteger(context, "angle"), 6.0, 1.5);
        return 1;
    }
}
