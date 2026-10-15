package com.meakaandre.siftec.command;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.block.NodeBlock;
import com.meakaandre.siftec.block.NodePadBlock;
import com.meakaandre.siftec.building.BuildingDef;
import com.meakaandre.siftec.building.Buildings;
import com.meakaandre.siftec.building.Placement;
import com.meakaandre.siftec.node.Node;
import com.meakaandre.siftec.node.NodeLook;
import com.meakaandre.siftec.node.NodeMap;
import com.meakaandre.siftec.node.NodePlacer;
import com.meakaandre.siftec.node.NodeRegrowth;
import com.meakaandre.siftec.node.NodeSavedData;
import com.meakaandre.siftec.node.NodeSites;
import com.meakaandre.siftec.node.NodeType;
import com.meakaandre.siftec.node.Purity;
import com.meakaandre.siftec.registry.ModBlocks;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.zurrtum.create.content.kinetics.base.BlockBreakingKineticBlockEntity;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.FakePlayer;
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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Selftests and camera spots for node sites, run by the automated test on an ordinary world. Every line of output
 * starts with "SELFTEST world"; the checks say PASS or FAIL, and each test ends with a count.
 * <ul>
 * <li>{@code worldtest pads <n>}: places the n nodes nearest spawn and checks each site: a level patch of its stone,
 * the node exactly its real ore block, kept as a node of the map's purity by its position, the clump over it; then
 * tries everything that could take a patch block or the node away (a survival player, two big explosions, a piston),
 * breaks the node as a drill does (air until it grows back) and, while it is empty, tries to place a block and pour
 * water into it and drops a block into it.</li>
 * <li>{@code worldtest regrow}: on a real node of each purity, clears the clump, mines the node by hand with a pickaxe
 * and times how long it takes to grow back, against its purity's regrowth time.</li>
 * <li>{@code worldtest drops}: every ore node's block drops what that block always drops, with a pickaxe, Fortune and
 * Silk Touch.</li>
 * <li>{@code worldtest restart setup|check}: mines a node and holds it empty over a server restart, then lets it grow
 * back.</li>
 * <li>{@code worldtest clump}: tries to put the test miner (a Build Gun node building) over a real node with its clump
 * still there, then mines the clump by hand and tries again; also off any node, and a second one on the same node.</li>
 * <li>{@code worldtest showroom}: a row of sites in the sky, every node look at each purity and one mined;
 * {@code worldtest look ...} puts every player at a camera spot for screenshots.</li>
 * </ul>
 */
public final class NodeTests {
    private NodeTests() {
    }

    private static final List<Monitor> MONITORS = new ArrayList<>();
    private static final int SHOW_Y = 200, SHOW_DZ = 48;
    /** Where the clump test left its test miner, for the camera. */
    private static BlockPos clumpSite;

    private interface Monitor {
        boolean tick();
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            var angle = Commands.argument("angle", IntegerArgumentType.integer(0, 8));
            var worldtest = Commands.literal("worldtest")
                .then(Commands.literal("pads").then(Commands.argument("count", IntegerArgumentType.integer(1, 40)).executes(NodeTests::pads)))
                .then(Commands.literal("regrow").executes(NodeTests::regrow))
                .then(Commands.literal("drops").executes(NodeTests::drops))
                .then(Commands.literal("restart").then(Commands.literal("setup").executes(c -> restart(c, true)))
                    .then(Commands.literal("check").executes(c -> restart(c, false))))
                .then(Commands.literal("clump").executes(NodeTests::clump))
                .then(Commands.literal("showroom").executes(NodeTests::showroom))
                .then(Commands.literal("look")
                    .then(Commands.literal("showroom").then(Commands.argument("col", IntegerArgumentType.integer(0, 40))
                        .then(Commands.argument("row", IntegerArgumentType.integer(0, 3)).then(angle.executes(c -> lookShowroom(c, 0))
                            .then(Commands.argument("dist", IntegerArgumentType.integer(2, 120)).executes(c -> lookShowroom(c, IntegerArgumentType.getInteger(c, "dist"))))))))
                    .then(Commands.literal("node").then(Commands.argument("type", StringArgumentType.word())
                        .then(Commands.argument("angle", IntegerArgumentType.integer(0, 8)).executes(NodeTests::lookNode))))
                    .then(Commands.literal("clump").then(Commands.argument("angle", IntegerArgumentType.integer(0, 8)).executes(NodeTests::lookClump))));
            dispatcher.register(Commands.literal("siftec").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).then(worldtest));
        });
        // a monitor may start others: they join the list for the next tick
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            List<Monitor> running = new ArrayList<>(MONITORS);
            MONITORS.clear();
            running.removeIf(Monitor::tick);
            MONITORS.addAll(0, running);
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            MONITORS.clear();
            clumpSite = null;
        });
    }

    private static void report(CommandSourceStack source, String text) {
        String line = "SELFTEST world " + text;
        Siftec.LOGGER.info(line);
        source.sendSuccess(() -> Component.literal(line), false);
    }

    /** One check: PASS or FAIL, counted into {@code tally} ({passed, failed}). */
    private static void check(CommandSourceStack source, int[] tally, boolean ok, String what) {
        tally[ok ? 0 : 1]++;
        report(source, (ok ? "PASS " : "FAIL ") + what);
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
        if (!keep) release(level, node);
    }

    private static void release(ServerLevel level, Node node) {
        int cx = node.x() >> 4, cz = node.z() >> 4;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) level.setChunkForced(cx + dx, cz + dz, false);
    }

    /** The node's position (its ore grown, or empty), or null if the site has none. */
    private static BlockPos core(ServerLevel level, Node node) {
        int y = NodeSavedData.get(level.getServer()).height(node.key());
        if (y == NodeSavedData.NO_HEIGHT || y == NodePlacer.NO_PLACE) return null;
        BlockPos pos = new BlockPos(node.x(), y + 1, node.z());
        if (node.type().isFluid()) return level.getBlockState(pos).getBlock() instanceof NodeBlock ? pos : null;
        return NodeSites.isSite(level, pos) ? pos : null;
    }

    private static String key(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).toString();
    }

    /** True if the node at {@code pos} is kept as this look and purity, and its block is exactly the look's real block. */
    private static boolean exactNode(ServerLevel level, BlockPos pos, NodeLook look, Purity purity) {
        var site = NodeSites.at(level, pos);
        Block want = look.nodeBlock();
        return site != null && site.look.equals(look) && site.purity == purity && want != null && level.getBlockState(pos).getBlock() == want
            && level.getBlockState(pos) == want.defaultBlockState();
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

    /**
     * Nodes the hand-mining tests may use: past the 12 nearest (the pad test blows a charge on one of those), never iron
     * or copper (the other selftests put machines on the nearest of those), only surface nodes that can be mined.
     */
    private static List<Node> mineable(ServerLevel level) {
        List<Node> all = nearest(level, 72, n -> true);
        return all.subList(Math.min(12, all.size()), all.size()).stream().filter(n -> !n.type().isFluid() && n.y() == Node.SURFACE
            && n.type() != NodeType.IRON && n.type() != NodeType.COPPER && NodeLook.of(n).output() != Items.AIR).toList();
    }

    private static String id(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).getPath();
    }

    // ---- sites

    /** What a site check found. */
    public record SiteReport(boolean ok, String text) {
    }

    /** The patch, the node and the clump of a placed site, in one line. */
    public static SiteReport site(ServerLevel level, Node node) {
        int y = NodeSavedData.get(level.getServer()).height(node.key());
        if (y == NodeSavedData.NO_HEIGHT || y == NodePlacer.NO_PLACE) return new SiteReport(false, "no site (height " + (y == NodePlacer.NO_PLACE ? "none fit" : "not chosen") + ")");
        NodeLook look = NodeLook.of(node);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int cells = 0, level0 = 0, offLevel = 0, unsupported = 0, fill = 0;
        Block patch = ModBlocks.PATCHES.get(look.patchName()).get();
        List<String> odd = new ArrayList<>();
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                int x = node.x() + dx, z = node.z() + dz;
                if (dx * dx + dz * dz > NodePlacer.PAD_RADIUS * NodePlacer.PAD_RADIUS) continue;
                cells++;
                BlockState at = level.getBlockState(pos.set(x, y, z));
                if (at.is(patch)) level0++;
                else if (odd.size() < 4) odd.add(dx + "," + dz + "=" + id(at.getBlock()));
                // the fill under the patch is the same block, so only patch blocks above it count as off its level
                for (int d = 1; d <= 4; d++) if (level.getBlockState(pos.set(x, y + d, z)).getBlock() instanceof NodePadBlock) offLevel++;
                BlockState under = level.getBlockState(pos.set(x, y - 1, z));
                if (under.isAir() || !under.getFluidState().isEmpty()) unsupported++;
                for (int d = 1; level.getBlockState(pos.set(x, y - d, z)).is(patch) && d < 64; d++) fill++;
            }
        }
        BlockPos corePos = new BlockPos(node.x(), y + 1, node.z());
        BlockState core = level.getBlockState(corePos);
        boolean nodeOk = look.fluid() ? core.getBlock() instanceof NodeBlock nb && nb.look.equals(look) && core.getValue(NodeBlock.PURITY) == node.purity()
            : exactNode(level, corePos, look, node.purity());
        // the clump: every cell it should fill, and what is in them
        List<BlockPos> clump = NodePlacer.clumpCells(node, corePos);
        int present = 0, stone = 0, ore = 0, raw = 0;
        for (BlockPos c : clump) {
            BlockState s = level.getBlockState(c);
            if (s.isAir() || s.canBeReplaced()) continue;
            present++;
            if (s.is(look.patchStone().getBlock())) stone++;
            else if (s.is(look.oreState().getBlock())) ore++;
            else if (s.is(look.clumpState().getBlock())) raw++;
        }
        boolean patchOk = level0 * 10 >= cells * 9 && offLevel == 0;
        boolean clumpOk = present == clump.size();
        String text = look.id() + " (" + look.patchName() + " patch) at y " + y + ": " + level0 + "/" + cells + " patch blocks level" + (odd.isEmpty() ? "" : " (others " + odd + ")")
            + ", " + offLevel + " above that level, " + unsupported + " with air or fluid under, " + fill + " fill blocks; node "
            + (nodeOk ? key(core.getBlock()) + " " + node.purity().getSerializedName() + (look.fluid() ? "" : ", a node by its position")
                : "WRONG (" + core + ", kept as " + NodeSites.at(level, corePos) + ", map says " + look.id() + " " + look.node() + " " + node.purity().getSerializedName() + ")")
            + "; clump " + present + "/" + clump.size() + " blocks: " + stone + " stone, " + ore + " ore, " + raw + " raw";
        return new SiteReport(patchOk && nodeOk && clumpOk, text);
    }

    /** For the other world tests: the site report as text. */
    public static String padReport(ServerLevel level, Node node) {
        return site(level, node).text();
    }

    private static int pads(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        NodeMap.settle(level);
        int count = IntegerArgumentType.getInteger(context, "count");
        List<Node> nodes = nearest(level, count, n -> true);
        int[] tally = new int[2];
        Node first = null;
        long start = System.nanoTime();
        Map<NodeType, Integer> starters = new EnumMap<>(NodeType.class);
        BlockPos o = origin();
        for (Node node : nodes) {
            place(level, node, false);
            SiteReport r = site(level, node);
            check(source, tally, r.ok(), "site " + node.type().id() + " " + node.purity().getSerializedName() + " at " + node.x() + "," + node.z() + ": " + r.text());
            if (node.purity() == Purity.NORMAL && node.distanceTo(o.getX(), o.getZ()) <= 256) starters.merge(node.type(), 1, Integer::sum);
            // the blast test keeps off the iron and copper nodes the other selftests put machines on
            if (first == null && node.y() == Node.SURFACE && !node.type().isFluid() && node.type() != NodeType.IRON && node.type() != NodeType.COPPER && core(level, node) != null) first = node;
        }
        report(source, "pads: " + nodes.size() + " sites placed in " + (System.nanoTime() - start) / 1_000_000 + " ms");
        for (NodeType t : new NodeType[]{NodeType.IRON, NodeType.COPPER, NodeType.STONE}) {
            check(source, tally, starters.getOrDefault(t, 0) > 0, "a normal " + t.id() + " node within 256 blocks of spawn");
        }
        report(source, "pads result: " + tally[0] + " passed, " + tally[1] + " failed");
        int[] breaking = new int[2];
        if (first != null) breakTest(source, level, first, breaking);
        else check(source, breaking, false, "a surface node to try breaking");
        return 1;
    }

    /** A survival player with a pickaxe, standing next to {@code target}. */
    private static FakePlayer miner(ServerLevel level, BlockPos target) {
        FakePlayer player = FakePlayer.get(level);
        // through creative and back, so the abilities really are a survival player's whatever another test left
        player.gameMode.changeGameModeForPlayer(GameType.CREATIVE);
        player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_PICKAXE));
        player.setPos(target.getX() + 0.5, target.getY() + 1, target.getZ() - 1.5);
        return player;
    }

    /**
     * Tries everything that could take a patch block or the node away (a survival player on the patch, two big
     * explosions, a piston), then breaks the node as a drill does: it drops what its block drops and leaves air, nothing
     * can be placed or poured into the empty node, a block dropped into it does not stop it, and it grows back into the
     * same block.
     */
    private static void breakTest(CommandSourceStack source, ServerLevel level, Node node, int[] tally) {
        place(level, node, true);
        BlockPos core = core(level, node);
        NodeLook look = NodeLook.of(node);
        int y = core.getY() - 1;
        BlockPos edge = new BlockPos(node.x() + 3, y, node.z());
        BlockState pad = level.getBlockState(edge);
        BlockState live = level.getBlockState(core);
        TagKey<Block> nonMovable = TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("create", "non_movable"));
        check(source, tally, pad.getDestroySpeed(level, edge) < 0 && pad.getPistonPushReaction() == PushReaction.IMMOVEABLE && pad.is(nonMovable)
                && !BlockBreakingKineticBlockEntity.isBreakable(pad, pad.getDestroySpeed(level, edge)),
            "patch " + id(pad.getBlock()) + " is unbreakable: hardness " + pad.getDestroySpeed(level, edge) + ", piston " + pad.getPistonPushReaction()
                + ", create:non_movable " + pad.is(nonMovable) + ", a Create drill could break it " + BlockBreakingKineticBlockEntity.isBreakable(pad, pad.getDestroySpeed(level, edge)));
        check(source, tally, exactNode(level, core, look, node.purity()) && BlockBreakingKineticBlockEntity.isBreakable(live, live.getDestroySpeed(level, core)),
            "node at " + core.toShortString() + " is exactly " + key(live.getBlock()) + " (" + look.node() + " wanted), a node by its position ("
                + NodeSites.at(level, core) + "), and can be mined and drilled: hardness " + live.getDestroySpeed(level, core));
        // a survival player digging the patch the way the client would: start and finish breaking
        FakePlayer player = miner(level, edge);
        float progress = pad.getDestroyProgress(player, level, edge);
        player.gameMode.handleBlockBreakAction(edge, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, Direction.UP, level.getMaxY(), 0);
        player.gameMode.handleBlockBreakAction(edge, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, Direction.UP, level.getMaxY(), 1);
        check(source, tally, level.getBlockState(edge).is(pad.getBlock()), "a survival player cannot dig the patch (progress per tick " + progress + ")");
        // two big charges on the patch and the node
        int padBefore = countPatch(level, node, y);
        level.explode(null, edge.getX() + 0.5, y + 1.5, edge.getZ() + 0.5, 6f, Level.ExplosionInteraction.TNT);
        level.explode(null, node.x() + 0.5, y + 3.5, node.z() + 0.5, 6f, Level.ExplosionInteraction.TNT);
        int padAfter = countPatch(level, node, y);
        BlockState after = level.getBlockState(core);
        check(source, tally, padAfter == padBefore && exactNode(level, core, look, node.purity()),
            "two power-6 explosions: patch blocks " + padBefore + " -> " + padAfter + ", node still " + key(after.getBlock()) + " (the clump may go: "
                + NodePlacer.clumpLeft(level, core) + " blocks left)");
        // a piston against the node's side, with room for it to push: it must not move the ore
        BlockPos piston = core.east(), power = core.east(2), beyond = core.west();
        for (BlockPos c : new BlockPos[]{piston, power, beyond, beyond.above(), piston.above()}) level.setBlock(c, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(piston, Blocks.PISTON.defaultBlockState().setValue(net.minecraft.world.level.block.piston.PistonBaseBlock.FACING, Direction.WEST), Block.UPDATE_ALL);
        level.setBlock(power, Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        long start = level.getGameTime();
        MONITORS.add(new Monitor() {
            /** 0 waiting on the piston; 1 broken, waiting for air; 2 empty (placing into it was tried); 3 done. */
            int phase;
            long brokenAt, emptySeen = -1;
            Map<String, Integer> drops;
            List<ItemStack> expected;
            boolean placeRefused, pourRefused, stillAir, sandIn;

            @Override
            public boolean tick() {
                long t = level.getGameTime() - start;
                if (phase == 0) {
                    if (t < 6) return false;
                    BlockState pushed = level.getBlockState(beyond);
                    check(source, tally, exactNode(level, core, look, node.purity()) && !look.isNodeBlock(pushed),
                        "a powered piston against the node: node still " + key(level.getBlockState(core).getBlock()) + ", beside it " + key(pushed.getBlock())
                            + ", piston " + key(level.getBlockState(piston).getBlock()) + " " + level.getBlockState(piston));
                    level.setBlock(power, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    level.setBlock(piston, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    // broken the way a Create drill breaks a block (no player): what its block drops, and air left
                    clearDrops(level, core);
                    expected = Block.getDrops(level.getBlockState(core), level, core, null);
                    level.destroyBlock(core, true);
                    drops = clearDrops(level, core);
                    brokenAt = level.getGameTime();
                    phase = 1;
                    return false;
                }
                BlockState now = level.getBlockState(core);
                long since = level.getGameTime() - brokenAt;
                if (phase == 1) {
                    boolean air = now.isAir();
                    Map<String, Integer> want = new LinkedHashMap<>();
                    for (ItemStack e : expected) want.merge(BuiltInRegistries.ITEM.getKey(e.getItem()).toString(), e.getCount(), Integer::sum);
                    check(source, tally, air && NodeSites.isSite(level, core) && drops.keySet().equals(want.keySet()) && !drops.isEmpty(),
                        "node broken without a player (as a drill does): the spot is " + key(now.getBlock()) + " (air wanted), still a node " + NodeSites.isSite(level, core)
                            + "; dropped " + drops + ", its block's own drops " + want);
                    // nothing goes into the empty node: a player placing stone on the patch top there, and a water bucket
                    FakePlayer hand = miner(level, core);
                    hand.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE));
                    var hit = new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(core.below()).add(0, 0.5, 0), Direction.UP, core.below(), false);
                    InteractionResult placed = hand.gameMode.useItemOn(hand, level, hand.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
                    placeRefused = level.getBlockState(core).isAir();
                    boolean poured = ((net.minecraft.world.item.BucketItem) Items.WATER_BUCKET).emptyContents(hand, level, core, hit);
                    pourRefused = !poured && level.getFluidState(core).isEmpty() && level.getBlockState(core).isAir();
                    check(source, tally, placeRefused && pourRefused, "the empty node takes nothing: a player placing stone into it (" + placed + ") leaves "
                        + key(level.getBlockState(core).getBlock()) + ", a water bucket poured " + poured + ", fluid there now " + !level.getFluidState(core).isEmpty());
                    emptySeen = since;
                    phase = 2;
                    return false;
                }
                if (phase == 2) {
                    // something in the way (as a falling block lands there) does not stop it
                    if (!sandIn && since >= 3) {
                        level.setBlock(core, Blocks.SAND.defaultBlockState(), Block.UPDATE_ALL);
                        sandIn = true;
                    }
                    if (look.isNodeBlock(now) || since > NodeRegrowth.ticks(node.purity()) + 100) {
                        int want = NodeRegrowth.ticks(node.purity());
                        check(source, tally, exactNode(level, core, look, node.purity()) && Math.abs(since - want) <= 2,
                            "the empty node, with sand dropped into it, grew back after " + since + " ticks (its purity's regrowth is " + want + "): now "
                                + key(now.getBlock()) + ", " + look.node() + " wanted");
                        release(level, node);
                        report(source, "break result: " + tally[0] + " passed, " + tally[1] + " failed");
                        return true;
                    }
                }
                return false;
            }
        });
    }

    private static int countPatch(ServerLevel level, Node node, int y) {
        int n = 0;
        for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) {
            if (level.getBlockState(new BlockPos(node.x() + dx, y, node.z() + dz)).getBlock() instanceof NodePadBlock) n++;
        }
        return n;
    }

    /** Mines every block of the clump left round the node block at {@code core}, as a survival player; what dropped is counted and cleared. */
    private static int mineClump(ServerLevel level, Node node, BlockPos core) {
        int mined = 0;
        // top down, so nothing is left standing on air
        List<BlockPos> cells = new ArrayList<>(NodePlacer.clumpCells(node, core));
        cells.sort((a, b) -> Integer.compare(b.getY(), a.getY()));
        for (BlockPos c : cells) {
            BlockState s = level.getBlockState(c);
            if (s.isAir() || s.canBeReplaced()) continue;
            if (miner(level, c).gameMode.destroyBlock(c)) mined++;
        }
        clearDrops(level, core);
        return mined;
    }

    /** The items lying round a spot, by item, removed from the world. */
    private static Map<String, Integer> clearDrops(ServerLevel level, BlockPos at) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, new AABB(at).inflate(4))) {
            out.merge(BuiltInRegistries.ITEM.getKey(e.getItem().getItem()).toString(), e.getItem().getCount(), Integer::sum);
            e.discard();
        }
        return out;
    }

    // ---- regrowth

    private static int regrow(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        NodeMap.settle(level);
        Map<Purity, Node> picked = new EnumMap<>(Purity.class);
        for (Node node : mineable(level)) picked.putIfAbsent(node.purity(), node);
        int[] tally = new int[2];
        check(source, tally, picked.size() == Purity.values().length, "a node of each purity to mine: " + picked);
        // the control: an ordinary coal ore block in the sky over spawn, mined the same way, so a node is held to what a
        // vanilla ore does for this fake player (should it ever get no drops at all)
        boolean[] control = {false};
        BlockPos ore = origin().atY(300);
        level.getChunk(ore);
        level.setBlock(ore, Blocks.COAL_ORE.defaultBlockState(), Block.UPDATE_ALL);
        boolean minedOre = miner(level, ore).gameMode.destroyBlock(ore);
        Map<String, Integer> oreDrops = clearDrops(level, ore);
        control[0] = oreDrops.getOrDefault("minecraft:coal", 0) > 0;
        report(source, "regrow control: vanilla coal ore mined by the same fake player: removed " + minedOre + ", dropped " + oreDrops);
        int[] left = {picked.size()};
        // the sites' chunks are forced now; their entities (the drops) only count a few ticks later
        for (Node node : picked.values()) place(level, node, true);
        MONITORS.add(new Monitor() {
            int wait = 10;

            @Override
            public boolean tick() {
                if (--wait > 0) return false;
                for (Map.Entry<Purity, Node> entry : picked.entrySet()) mineAndTime(source, level, entry.getValue(), tally, left, control);
                if (left[0] == 0) report(source, "regrow result: " + tally[0] + " passed, " + tally[1] + " failed");
                return true;
            }
        });
        return 1;
    }

    /** Mines one node by hand (its clump first) and times its regrowth. */
    private static void mineAndTime(CommandSourceStack source, ServerLevel level, Node node, int[] tally, int[] left, boolean[] control) {
        {
            BlockPos core = core(level, node);
            if (core == null) {
                check(source, tally, false, "regrow " + node + ": no node block (" + padReport(level, node) + ")");
                left[0]--;
                return;
            }
            int cleared = mineClump(level, node, core);
            NodeLook look = NodeLook.of(node);
            BlockState before = level.getBlockState(core);
            FakePlayer hand = miner(level, core);
            // what the loot table gives for this player and tool, and whether the game counts the tool as right for it
            String why = "mode " + hand.gameMode.getGameModeForPlayer() + ", instabuild " + hand.getAbilities().instabuild + ", right tool " + hand.hasCorrectToolForDrops(before) + ", loot "
                + Block.getDrops(before, level, core, null, hand, hand.getMainHandItem());
            int held = hand.getInventory().countItem(look.output());
            boolean mined = hand.gameMode.destroyBlock(core);
            Map<String, Integer> drops = clearDrops(level, core);
            why += ", into the player's inventory " + (hand.getInventory().countItem(look.output()) - held)
                + ", items within 16 " + level.getEntitiesOfClass(ItemEntity.class, new AABB(core).inflate(16)).size();
            String want = BuiltInRegistries.ITEM.getKey(look.output()).toString();
            List<ItemStack> loot = Block.getDrops(before, level, core, null, hand, hand.getMainHandItem());
            boolean lootOk = loot.size() == 1 && loot.getFirst().is(look.output()) && loot.getFirst().getCount() == 1;
            // what the player gets is the loot, unless the control shows this fake player gets no drops from any block
            boolean dropsOk = drops.getOrDefault(want, 0) == 1 || !control[0] && drops.isEmpty();
            check(source, tally, mined && lootOk && dropsOk, "regrow " + look.id() + " " + node.purity().getSerializedName() + ": cleared "
                + cleared + " clump blocks, mined " + id(before.getBlock()) + " by hand, its loot for a pickaxe is " + loot + " (expected 1 " + want + "), dropped " + drops
                + (control[0] ? "" : " (as for the vanilla ore of the control)") + "; " + why);
            long start = level.getGameTime();
            int expected = NodeRegrowth.ticks(node.purity());
            MONITORS.add(new Monitor() {
                boolean sawAir;

                @Override
                public boolean tick() {
                    BlockState now = level.getBlockState(core);
                    long t = level.getGameTime() - start;
                    if (now.isAir() && !sawAir) {
                        sawAir = true;
                        // a mined node is plain air, still a node of its purity, until it has grown back
                        var site = NodeSites.at(level, core);
                        check(source, tally, site != null && site.look.equals(look) && site.purity == node.purity() && !site.grown(),
                            "regrow " + look.id() + ": the mined node is " + key(now.getBlock()) + ", still a node (" + site + ")");
                    }
                    if (look.isNodeBlock(now) || t > expected + 100) {
                        boolean back = exactNode(level, core, look, node.purity());
                        check(source, tally, sawAir && back && Math.abs(t - expected) <= 2, "regrow " + look.id() + " " + node.purity().getSerializedName()
                            + ": grew back in " + t + " ticks (its purity's regrowth is " + expected + "), now " + key(now.getBlock()) + " (" + look.node() + " wanted)");
                        release(level, node);
                        if (--left[0] == 0) report(source, "regrow result: " + tally[0] + " passed, " + tally[1] + " failed");
                        return true;
                    }
                    return false;
                }
            });
        }
    }

    // ---- drops: an ore node's block is the real block, so it drops what that block always drops

    private static ItemStack tool(ServerLevel level, net.minecraft.resources.ResourceKey<net.minecraft.world.item.enchantment.Enchantment> enchantment, int lvl) {
        ItemStack pick = new ItemStack(Items.DIAMOND_PICKAXE);
        if (enchantment != null) pick.enchant(level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(enchantment), lvl);
        return pick;
    }

    private static Map<String, Integer> tally(List<ItemStack> stacks) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (ItemStack e : stacks) if (!e.isEmpty()) out.merge(BuiltInRegistries.ITEM.getKey(e.getItem()).toString(), e.getCount(), Integer::sum);
        return out;
    }

    /**
     * For every ore node look: the block is the look's real block; with a diamond pickaxe it is the right tool and drops
     * the resource; Fortune III gives at least as much and never anything else; Silk Touch gives the block itself.
     */
    private static int drops(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        int[] tally = new int[2];
        BlockPos at = origin().atY(310);
        level.getChunk(at);
        FakePlayer player = miner(level, at);
        for (NodeLook look : NodeLook.all()) {
            if (look.fluid()) continue;
            Block block = look.nodeBlock();
            if (block == null) {
                check(source, tally, look.node().startsWith("cgs:"), "drops " + look.id() + ": no block " + look.node());
                continue;
            }
            BlockState state = block.defaultBlockState();
            level.setBlock(at, state, Block.UPDATE_ALL);
            String want = look.drop();
            ItemStack plain = tool(level, null, 0);
            player.setItemInHand(InteractionHand.MAIN_HAND, plain);
            boolean right = player.hasCorrectToolForDrops(state);
            Map<String, Integer> p = tally(Block.getDrops(state, level, at, null, player, plain));
            int fortuneMost = 0;
            boolean fortuneOnly = true;
            for (int i = 0; i < 20; i++) {
                Map<String, Integer> f = tally(Block.getDrops(state, level, at, null, player, tool(level, net.minecraft.world.item.enchantment.Enchantments.FORTUNE, 3)));
                fortuneOnly &= f.keySet().equals(java.util.Set.of(want));
                fortuneMost = Math.max(fortuneMost, f.getOrDefault(want, 0));
            }
            Map<String, Integer> silk = tally(Block.getDrops(state, level, at, null, player, tool(level, net.minecraft.world.item.enchantment.Enchantments.SILK_TOUCH, 1)));
            String self = BuiltInRegistries.ITEM.getKey(block.asItem()).toString();
            // a stone node's block drops itself anyway
            boolean ok = right && p.keySet().equals(java.util.Set.of(want)) && fortuneOnly && silk.equals(Map.of(self, 1))
                && (want.equals(self) || look.type() == NodeType.COPPER || fortuneMost > p.getOrDefault(want, 0));
            check(source, tally, ok, "drops " + look.id() + " (" + key(block) + "): right tool " + right + ", pickaxe " + p + ", Fortune III up to " + fortuneMost
                + " " + want + " (only that " + fortuneOnly + "), Silk Touch " + silk);
        }
        level.setBlock(at, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        report(source, "drops result: " + tally[0] + " passed, " + tally[1] + " failed");
        return 1;
    }

    // ---- regrowth over a restart

    /** The node the restart test holds empty: one of the mineable ones the other tests leave alone. */
    private static Node restartNode(ServerLevel level) {
        List<Node> candidates = mineable(level);
        Map<Purity, Node> regrowing = new EnumMap<>(Purity.class);
        for (Node n : candidates) regrowing.putIfAbsent(n.purity(), n);
        for (int i = candidates.size() - 2; i >= 0; i--) {
            if (!regrowing.containsValue(candidates.get(i))) return candidates.get(i);
        }
        return null;
    }

    /**
     * {@code setup}: mines a node and holds it empty (its regrowth set far off, as a node whose chunk is not loaded
     * waits); {@code check}, after the restart: it is still a node of its look and purity, still empty and still held,
     * and let go it grows back into exactly its block at its purity's time.
     */
    private static int restart(CommandContext<CommandSourceStack> context, boolean setup) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        NodeMap.settle(level);
        int[] tally = new int[2];
        Node node = restartNode(level);
        if (node == null) {
            check(source, tally, false, "restart: no node to use");
            return 0;
        }
        place(level, node, true);
        BlockPos core = core(level, node);
        if (core == null) {
            check(source, tally, false, "restart " + node + ": no node (" + padReport(level, node) + ")");
            return 0;
        }
        NodeLook look = NodeLook.of(node);
        if (setup) {
            mineClump(level, node, core);
            level.destroyBlock(core, false);
            NodeSites.hold(level, core);
            check(source, tally, level.getBlockState(core).isAir() && NodeSites.ticksLeft(level, core) > 1_000_000,
                "restart setup " + look.id() + " at " + core.toShortString() + ": mined, empty and held over the restart (" + NodeSites.at(level, core) + ")");
            report(source, "restart setup result: " + tally[0] + " passed, " + tally[1] + " failed");
            return 1;
        }
        var site = NodeSites.at(level, core);
        check(source, tally, site != null && site.look.equals(look) && site.purity == node.purity() && !site.grown() && level.getBlockState(core).isAir(),
            "restart check " + look.id() + " at " + core.toShortString() + ": after the restart still a node, still empty: " + site + ", the spot " + key(level.getBlockState(core).getBlock()));
        NodeSites.release(level, core);
        long start = level.getGameTime();
        int want = NodeRegrowth.ticks(node.purity());
        MONITORS.add(() -> {
            long t = level.getGameTime() - start;
            BlockState now = level.getBlockState(core);
            if (!look.isNodeBlock(now) && t <= want + 100) return false;
            check(source, tally, exactNode(level, core, look, node.purity()) && Math.abs(t - want) <= 2,
                "restart check " + look.id() + ": let go after the restart, it grew back in " + t + " ticks (" + want + " for its purity) into " + key(now.getBlock())
                    + " (" + look.node() + " wanted)");
            release(level, node);
            report(source, "restart check result: " + tally[0] + " passed, " + tally[1] + " failed");
            return true;
        });
        return 1;
    }

    // ---- the clump and a Build Gun node building

    private static int clump(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        NodeMap.settle(level);
        int[] tally = new int[2];
        BuildingDef def = Buildings.def("test_miner");
        if (def == null) {
            check(source, tally, false, "clump: no test_miner building");
            return 0;
        }
        // a node the regrowth test does not use: the last of the mineable ones
        List<Node> candidates = mineable(level);
        Node node = candidates.isEmpty() ? null : candidates.getLast();
        if (node == null) {
            check(source, tally, false, "clump: no node to build on");
            return 0;
        }
        place(level, node, true);
        // the drops of the mined clump only count once the site's chunks have their entities in: a few ticks
        MONITORS.add(new Monitor() {
            int wait = 10;

            @Override
            public boolean tick() {
                if (--wait > 0) return false;
                clumpChecks(source, level, def, node, tally);
                return true;
            }
        });
        return 1;
    }

    private static void clumpChecks(CommandSourceStack source, ServerLevel level, BuildingDef def, Node node, int[] tally) {
        BlockPos core = core(level, node);
        if (core == null) {
            check(source, tally, false, "clump " + node + ": no node block (" + padReport(level, node) + ")");
            return;
        }
        // the miner's middle bottom cell is the node: the anchor is the front edge, one block south of it
        Placement p = new Placement(def, core.south(), Direction.NORTH, false);
        int left = NodePlacer.clumpLeft(level, core);
        Buildings.Check withClump = Buildings.check(level, p, null, null, true);
        check(source, tally, left > 0 && !withClump.ok() && "siftec.build_gun.clump".equals(withClump.reason),
            "clump " + NodeLook.of(node).id() + " at " + core.toShortString() + ": " + left + " clump blocks, the test miner is refused: " + withClump.message().getString());
        int mined = mineClump(level, node, core);
        Buildings.Check without = Buildings.check(level, p, null, null, true);
        check(source, tally, NodePlacer.clumpLeft(level, core) == 0 && without.ok(), "clump mined by hand (" + mined + " blocks): the test miner fits: " + without.message().getString());
        Buildings.Check placed = Buildings.place(level, p, null, null, true);
        check(source, tally, placed.ok() && exactNode(level, core, NodeLook.of(node), node.purity()),
            "test miner placed over the node, which stays: " + key(level.getBlockState(core).getBlock()));
        Buildings.Check second = Buildings.check(level, p, null, null, true);
        check(source, tally, !second.ok(), "a second miner on the same node is refused: " + second.message().getString());
        Placement off = new Placement(def, core.offset(0, 6, 12), Direction.NORTH, false);
        for (BlockPos c : BlockPos.betweenClosed(core.offset(-2, 6, 9), core.offset(2, 8, 13))) level.setBlock(c, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        Buildings.Check offNode = Buildings.check(level, off, null, null, true);
        check(source, tally, "siftec.build_gun.no_node".equals(offNode.reason), "the test miner off any node is refused: " + offNode.message().getString());
        clumpSite = core;
        report(source, "clump result: " + tally[0] + " passed, " + tally[1] + " failed");
    }

    // ---- the showroom and camera spots

    /** Every node look, in table order. */
    private static List<NodeLook> looks() {
        return new ArrayList<>(NodeLook.all());
    }

    private static BlockPos showCentre(int col, int row) {
        BlockPos o = origin();
        return new BlockPos(o.getX() - 110 + col * 10, SHOW_Y, o.getZ() + SHOW_DZ + row * 10);
    }

    /** A node of this look at this spot (stone nodes: the position decides the stone, so one is searched for nearby). */
    private static Node showNode(NodeLook look, BlockPos c, Purity purity) {
        if (look.type() != NodeType.STONE) return new Node(c.getX(), c.getZ(), look.type(), purity);
        for (int i = 0; i < 4096; i++) {
            Node n = new Node(c.getX(), c.getZ() + i * 100_000, NodeType.STONE, purity);
            if (NodeLook.of(n).equals(look)) return n;
        }
        return new Node(c.getX(), c.getZ(), NodeType.STONE, purity);
    }

    private static int showroom(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        NodeMap.settle(level);
        List<NodeLook> looks = looks();
        for (int col = 0; col < looks.size(); col++) {
            NodeLook look = looks.get(col);
            for (int row = 0; row < 4; row++) {
                BlockPos c = showCentre(col, row);
                level.getChunk(c);
                Purity purity = row < 3 ? Purity.values()[row] : Purity.NORMAL;
                BlockState patch = ModBlocks.PATCHES.get(look.patchName()).get().defaultBlockState();
                for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) {
                    if (dx * dx + dz * dz <= NodePlacer.PAD_RADIUS * NodePlacer.PAD_RADIUS) level.setBlockAndUpdate(c.offset(dx, 0, dz), patch);
                }
                Node node = showNode(look, c, purity);
                BlockPos core = c.above();
                if (row < 3) {
                    level.setBlockAndUpdate(core, NodePlacer.coreState(node));
                    // the impure row keeps its clump; the others show the node bare
                    if (row == 0) NodePlacer.placeClump(level, node, core);
                } else if (!look.fluid()) {
                    // the bottom row: mined, as it is while it grows back: the bare patch
                    level.setBlockAndUpdate(core, Blocks.AIR.defaultBlockState());
                } else {
                    level.setBlockAndUpdate(core, NodePlacer.coreState(node));
                }
            }
        }
        report(source, "showroom: " + looks.size() + " node looks: impure with clump, normal, pure, and mined, at y " + SHOW_Y + " from " + showCentre(0, 0).toShortString());
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
        double distance = dist > 0 ? dist : 4.5;
        look(context.getSource(), context.getSource().getServer().overworld(), c.getX() + 0.5, c.getY() + 1.5, c.getZ() + 0.5,
            IntegerArgumentType.getInteger(context, "angle"), distance, dist > 0 ? distance * 0.5 : 1.2);
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

    private static int lookClump(CommandContext<CommandSourceStack> context) {
        if (clumpSite == null) {
            report(context.getSource(), "look clump: the clump test has not run");
            return 0;
        }
        look(context.getSource(), context.getSource().getServer().overworld(), clumpSite.getX() + 0.5, clumpSite.getY() + 0.5, clumpSite.getZ() + 0.5,
            IntegerArgumentType.getInteger(context, "angle"), 6.0, 2.5);
        return 1;
    }
}
