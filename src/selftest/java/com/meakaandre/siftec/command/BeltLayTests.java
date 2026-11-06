package com.meakaandre.siftec.command;

import com.meakaandre.siftec.TestQueue;
import com.meakaandre.siftec.TestReport;
import com.meakaandre.siftec.building.BuildingIndex;
import com.meakaandre.siftec.building.BuildingTemplate;
import com.meakaandre.siftec.building.BuildingTests;
import com.meakaandre.siftec.building.Buildings;
import com.meakaandre.siftec.building.BuildingDef;
import com.meakaandre.siftec.building.ControllerBlockEntity;
import com.meakaandre.siftec.building.Placement;
import com.meakaandre.siftec.building.PortBlock;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.company.CompanyData;
import com.meakaandre.siftec.hub.Milestone;
import com.meakaandre.siftec.hub.Milestones;
import com.meakaandre.siftec.owner.Ownership;
import com.meakaandre.siftec.tweak.SelfMovingBelts;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.context.CommandContext;
import com.zurrtum.create.AllBlocks;
import com.zurrtum.create.AllDataComponents;
import com.zurrtum.create.AllItems;
import com.zurrtum.create.api.behaviour.BlockEntityBehaviour;
import com.zurrtum.create.content.kinetics.belt.BeltBlock;
import com.zurrtum.create.content.kinetics.belt.BeltBlockEntity;
import com.zurrtum.create.content.kinetics.belt.BeltPart;
import com.zurrtum.create.content.kinetics.belt.BeltSlope;
import com.zurrtum.create.content.kinetics.belt.behaviour.DirectBeltInputBehaviour;
import com.zurrtum.create.content.kinetics.belt.item.BeltConnectorItem;
import com.zurrtum.create.content.kinetics.belt.transport.TransportedItemStack;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Selftests for laying belts with no shafts, run by the smoke test: {@code /siftec selftest beltlay} (and
 * {@code beltlay restart} after the restart). A player with the belt item clicks two spots of plain ground and gets a
 * flat, a sloped or a vertical belt, which carries items at its company's milestone speed and feeds a building's port;
 * creative pays nothing, survival pays Create's one belt item and gets it back (and no shaft) when the belt is broken;
 * too long a line, a blocked cell and a building in the way are refused; two shafts clicked the old way still give
 * Create's belt. Every check prints "SELFTEST beltlay"; failures say FAIL.
 * <p>
 * The client part ({@code /siftec worldtest beltlay client ...}) gets a spot ready for the smoke script, which lays a
 * belt with the real client's clicks and photographs the preview line, then checks what it laid.
 */
public final class BeltLayTests {
    private static final int X = 16000, Y = 200, Z = -16000;
    private static final UUID TESTER = UUID.fromString("5e1f7e57-be17-4a1d-9000-000000000b17");
    private static final String COMPANY = "selftest_beltlay";
    private static final int MEASURE_TICKS = 10;
    /** The flat belt the restart check looks at again. */
    private static final BlockPos FLAT_A = new BlockPos(X, Y, Z), FLAT_B = new BlockPos(X + 7, Y, Z);
    /** The client part's belt: first end, last end, and the block put in its way for the red preview. */
    public static final BlockPos CLIENT_A = new BlockPos(X + 60, Y, Z + 30), CLIENT_B = CLIENT_A.east(7), CLIENT_BLOCK = CLIENT_A.east(3);

    private static final TestQueue LATER = new TestQueue();
    private static final TestReport REPORT = new TestReport("SELFTEST beltlay ");
    private static final TestReport CLIENT = new TestReport("SELFTEST beltlay-client ");

    private BeltLayTests() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> LATER.runDue(server.overworld().getGameTime()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> LATER.clear());
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
            Commands.literal("siftec").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("selftest").then(Commands.literal("beltlay").executes(BeltLayTests::run)
                    .then(Commands.literal("restart").executes(BeltLayTests::restart))))
                .then(Commands.literal("worldtest").then(Commands.literal("beltlay").then(Commands.literal("client")
                    .then(Commands.literal("setup").executes(c -> clientSetup(c.getSource())))
                    .then(Commands.literal("block").executes(c -> clientBlock(c.getSource(), true)))
                    .then(Commands.literal("clear").executes(c -> clientBlock(c.getSource(), false)))
                    .then(Commands.literal("check").executes(c -> clientCheck(c.getSource()))))))));
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static void later(ServerLevel level, int ticks, Runnable run) {
        LATER.add(level.getGameTime() + ticks, () -> REPORT.attempt(level.getServer().createCommandSourceStack(), "a later check", run));
    }

    private static void report(CommandSourceStack source, String text) {
        REPORT.report(source, text);
    }

    private static void expect(CommandSourceStack source, String what, boolean ok, String detail) {
        REPORT.expect(source, what, ok, detail);
    }

    /** Every milestone's belt token, lowest first. */
    private static List<String> beltTokens() {
        List<String> out = new ArrayList<>();
        for (Milestone m : Milestones.all()) for (String t : m.tokens()) if (t.startsWith("belt:") && !out.contains(t)) out.add(t);
        out.sort((a, b) -> Integer.compare(Integer.parseInt(a.substring(5)), Integer.parseInt(b.substring(5))));
        return out;
    }

    /** The test company (the tester is its owner), made afresh with the second belt tier done; its belt speed. */
    private static int company(ServerLevel level) {
        List<String> tokens = beltTokens();
        String token = tokens.size() > 1 ? tokens.get(1) : tokens.isEmpty() ? "belt:0" : tokens.getFirst();
        CompanyData data = CompanyData.get(level.getServer());
        Company c = new Company();
        c.id = COMPANY;
        c.name = COMPANY;
        c.members.add(TESTER.toString());
        c.owner = TESTER.toString();
        for (Milestone m : Milestones.all()) if (m.tokens().contains(token)) c.done.add(m.id());
        data.companies().put(COMPANY, c);
        data.setDirty();
        return SelfMovingBelts.itemsPerMinute(c);
    }

    private static FakePlayer tester(ServerLevel level, GameType mode, int belts) {
        FakePlayer p = FakePlayer.get(level, new GameProfile(TESTER, "selftest_beltlay"));
        p.getInventory().clearContent();
        p.setShiftKeyDown(false);
        p.setGameMode(mode);
        p.setYRot(180);
        p.setItemInHand(InteractionHand.MAIN_HAND, belts <= 0 ? ItemStack.EMPTY : new ItemStack(AllItems.BELT_CONNECTOR, belts));
        return p;
    }

    /** One right-click with what the tester holds, on a face of a block, as a player's click arrives on the server. */
    private static InteractionResult click(ServerLevel level, FakePlayer p, BlockPos block, Direction face) {
        // the belt item's 5-tick cooldown after a click: a fake player is not ticked, so its cooldowns are run on here
        for (int i = 0; i < 6; i++) p.getCooldowns().tick();
        p.setPos(block.getX() + 0.5, block.getY() + 1, block.getZ() + 2.5);
        Vec3 hit = Vec3.atCenterOf(block).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        ItemStack held = p.getMainHandItem();
        return p.gameMode.useItemOn(p, level, held, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, block, false));
    }

    /** Clicks the top of the ground under each end: the belt item's two clicks. The second click's result. */
    private static InteractionResult layOnGround(ServerLevel level, FakePlayer p, BlockPos a, BlockPos b) {
        click(level, p, a.below(), Direction.UP);
        return click(level, p, b.below(), Direction.UP);
    }

    private static @Nullable BeltBlockEntity beltAt(ServerLevel level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof BeltBlockEntity b ? b : null;
    }

    /** The cells of a straight belt line from a to b (a, then each step, then b). */
    private static List<BlockPos> cells(BlockPos a, BlockPos b) {
        BlockPos step = new BlockPos(Integer.signum(b.getX() - a.getX()), Integer.signum(b.getY() - a.getY()), Integer.signum(b.getZ() - a.getZ()));
        List<BlockPos> out = new ArrayList<>();
        int limit = 100;
        for (BlockPos at = a; limit-- > 0; at = at.offset(step)) {
            out.add(at);
            if (at.equals(b)) break;
        }
        return out;
    }

    /** How many cells of the line are belt blocks. */
    private static int belts(ServerLevel level, List<BlockPos> cells) {
        int n = 0;
        for (BlockPos p : cells) if (level.getBlockState(p).is(AllBlocks.BELT)) n++;
        return n;
    }

    /** A whole belt from a to b: every cell a belt block, a start and an end part, of one belt of that length. */
    private static boolean whole(ServerLevel level, BlockPos a, BlockPos b, BeltSlope slope) {
        List<BlockPos> cells = cells(a, b);
        if (belts(level, cells) != cells.size()) return false;
        BlockState first = level.getBlockState(a), last = level.getBlockState(b);
        BeltBlockEntity start = beltAt(level, a);
        return first.getValue(BeltBlock.PART) == BeltPart.START && last.getValue(BeltBlock.PART) == BeltPart.END
            && first.getValue(BeltBlock.SLOPE) == slope && start != null && start.isController() && start.beltLength == cells.size();
    }

    private static String describe(ServerLevel level, BlockPos a, BlockPos b) {
        List<BlockPos> cells = cells(a, b);
        BlockState first = level.getBlockState(a);
        return belts(level, cells) + "/" + cells.size() + " belt cells; first " + first.getBlock()
            + (first.hasProperty(BeltBlock.PART) ? " " + first.getValue(BeltBlock.PART) + " " + first.getValue(BeltBlock.SLOPE) + " " + first.getValue(BeltBlock.HORIZONTAL_FACING) : "");
    }

    /** True if both ends of the belt show no pulley (Create's belt ends have one when laid on shafts). */
    private static boolean noPulleys(ServerLevel level, BlockPos a, BlockPos b) {
        BeltBlockEntity x = beltAt(level, a), y = beltAt(level, b);
        return x != null && y != null && !x.hasPulley() && !y.hasPulley();
    }

    private static boolean feed(ServerLevel level, BlockPos beltStart, Direction along, ItemStack one) {
        DirectBeltInputBehaviour in = BlockEntityBehaviour.get(level, beltStart, DirectBeltInputBehaviour.TYPE);
        if (in == null || !in.canInsertFromSide(along)) return false;
        return in.handleInsertion(one.copy(), along, false).isEmpty();
    }

    /** The furthest along item on a belt, or -1. */
    private static float lead(ServerLevel level, BlockPos anySegment) {
        BeltBlockEntity b = beltAt(level, anySegment);
        if (b == null || b.getInventory() == null) return -1;
        float best = -1;
        for (TransportedItemStack t : b.getInventory().getTransportedItems()) best = Math.max(best, t.beltPosition);
        return best;
    }

    private static int dropped(ServerLevel level, AABB box, Item item) {
        int n = 0;
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, box)) if (e.getItem().is(item)) n += e.getItem().getCount();
        return n;
    }

    private static void clearDrops(ServerLevel level, AABB box) {
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, box)) e.discard();
    }

    private static AABB around(BlockPos a, BlockPos b) {
        return new AABB(Vec3.atLowerCornerOf(a), Vec3.atLowerCornerOf(b).add(1, 1, 1)).inflate(3);
    }

    private static void shaft(ServerLevel level, BlockPos pos, Direction.Axis axis) {
        level.setBlock(pos, AllBlocks.SHAFT.defaultBlockState().setValue(BlockStateProperties.AXIS, axis), Block.UPDATE_ALL);
    }

    /** Measures an item fed onto a belt's start: its speed in items a minute must be the company's. */
    private static void speed(CommandSourceStack source, ServerLevel level, String what, BlockPos start, Direction along, int perMinute) {
        later(level, 40, () -> feed(level, start, along, new ItemStack(Items.IRON_INGOT)));
        float[] from = {0};
        later(level, 45, () -> from[0] = lead(level, start));
        later(level, 45 + MEASURE_TICKS, () -> {
            float now = lead(level, start);
            float measured = (now - from[0]) / MEASURE_TICKS * 1200;
            expect(source, what + ": an item rides it at the milestone's " + perMinute + " items/min",
                perMinute > 0 && from[0] >= 0 && Math.abs(measured - perMinute) <= Math.max(1, perMinute * 0.03f),
                String.format(Locale.ROOT, "measured %.1f items/min, %.3f -> %.3f blocks", measured, from[0], now));
        });
    }

    // ------------------------------------------------------------------------------------------------ the test

    private static int run(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        REPORT.reset();
        BuildingTests.platform(level, X - 6, Z - 6, X + 80, Z + 50, Y, 12);
        clearDrops(level, new AABB(X - 6, Y - 2, Z - 6, X + 80, Y + 12, Z + 50));
        int perMinute = company(level);
        expect(source, "the test company's belts run at a milestone speed", perMinute > 0, perMinute + " items/min");
        REPORT.attempt(source, "flat and sloped", () -> flatAndSloped(source, level, perMinute));
        REPORT.attempt(source, "vertical and mixed", () -> verticalAndMixed(source, level));
        REPORT.attempt(source, "port", () -> port(source, level));
        REPORT.attempt(source, "cost", () -> cost(source, level));
        REPORT.attempt(source, "refusals", () -> refusals(source, level));
        REPORT.attempt(source, "old way", () -> oldWay(source, level));
        later(level, 400, () -> report(source, "done: " + REPORT.totals()));
        report(source, "test site ready; the checks report over the next twenty seconds");
        return 1;
    }

    /** A flat and a sloped belt laid by two clicks on plain ground, in creative: no shafts, no cost, milestone speed. */
    private static void flatAndSloped(CommandSourceStack source, ServerLevel level, int perMinute) {
        FakePlayer p = tester(level, GameType.CREATIVE, 1);
        InteractionResult first = click(level, p, FLAT_A.below(), Direction.UP);
        ItemStack held = p.getMainHandItem();
        expect(source, "flat: the first click on plain ground marks the belt's first end in front of the face",
            first.consumesAction() && FLAT_A.equals(held.get(AllDataComponents.BELT_FIRST_SHAFT)), first + ", first end " + held.get(AllDataComponents.BELT_FIRST_SHAFT));
        InteractionResult second = click(level, p, FLAT_B.below(), Direction.UP);
        expect(source, "flat: the second click lays a flat belt between the two spots of plain ground, no shafts",
            second.consumesAction() && whole(level, FLAT_A, FLAT_B, BeltSlope.HORIZONTAL)
                && level.getBlockState(FLAT_A).getValue(BeltBlock.HORIZONTAL_FACING) == Direction.EAST, second + "; " + describe(level, FLAT_A, FLAT_B));
        expect(source, "flat: its ends are plain belt ends with no pulley", noPulleys(level, FLAT_A, FLAT_B), "");
        expect(source, "flat: creative takes no belt item, and the first end is forgotten",
            held.is(AllItems.BELT_CONNECTOR) && held.getCount() == 1 && !held.has(AllDataComponents.BELT_FIRST_SHAFT), held.toString());
        BeltBlockEntity flat = beltAt(level, FLAT_A);
        expect(source, "flat: the belt is the clicking player's company's", flat != null && COMPANY.equals(flat.getAttached(Ownership.OWNER)),
            flat == null ? "no belt" : String.valueOf(flat.getAttached(Ownership.OWNER)));
        speed(source, level, "flat", FLAT_A, Direction.EAST, perMinute);

        // sloped: up five blocks; the far end is the air on top of a pillar
        BlockPos a = new BlockPos(X, Y, Z + 6), b = a.offset(5, 5, 0);
        for (int h = 0; h < 5; h++) level.setBlock(b.below(1 + h), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        InteractionResult up = layOnGround(level, p, a, b);
        expect(source, "sloped: two clicks on plain ground lay a belt sloping up, no shafts",
            up.consumesAction() && whole(level, a, b, BeltSlope.UPWARD) && noPulleys(level, a, b), up + "; " + describe(level, a, b));
        speed(source, level, "sloped", a, Direction.EAST, perMinute);
    }

    /** A vertical belt up a wall's face, and a belt from a shaft to plain ground (the shaft end keeps its pulley). */
    private static void verticalAndMixed(CommandSourceStack source, ServerLevel level) {
        FakePlayer p = tester(level, GameType.CREATIVE, 1);
        BlockPos a = new BlockPos(X + 12, Y, Z), b = a.above(4);
        // the top end is the air in front of a block's west face
        level.setBlock(b.east(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        click(level, p, a.below(), Direction.UP);
        InteractionResult r = click(level, p, b.east(), Direction.WEST);
        expect(source, "vertical: a click on the ground and one on a wall's face lay a vertical belt, no shafts",
            r.consumesAction() && whole(level, a, b, BeltSlope.VERTICAL) && noPulleys(level, a, b), r + "; " + describe(level, a, b));

        BlockPos s = new BlockPos(X + 16, Y, Z), e = s.east(5);
        shaft(level, s, Direction.Axis.Z);
        click(level, p, s, Direction.UP);
        InteractionResult m = click(level, p, e.below(), Direction.UP);
        BeltBlockEntity onShaft = beltAt(level, s), inAir = beltAt(level, e);
        expect(source, "mixed: from a shaft to plain ground, the shaft end keeps its pulley and the air end has none",
            m.consumesAction() && whole(level, s, e, BeltSlope.HORIZONTAL) && onShaft != null && onShaft.hasPulley() && inAir != null && !inAir.hasPulley(),
            m + "; " + describe(level, s, e));
    }

    /** A shaftless belt up to a Storage Container's input port: what goes on it ends up in the building. */
    private static void port(CommandSourceStack source, ServerLevel level) {
        BuildingDef def = Buildings.def("storage_container");
        BuildingTemplate t = def == null ? null : BuildingTemplate.of(def);
        if (t == null) {
            expect(source, "port: the Storage Container exists", false, "");
            return;
        }
        Placement placement = new Placement(def, new BlockPos(X + 30, Y, Z + 20), Direction.NORTH, false);
        Company owner = CompanyData.get(level.getServer()).byId(COMPANY);
        Buildings.Check check = Buildings.place(level, placement, owner, null, true);
        ControllerBlockEntity c = level.getBlockEntity(placement.at(t.controller)) instanceof ControllerBlockEntity ce ? ce : null;
        BlockPos in = null;
        for (BuildingTemplate.Cell cell : t.members) {
            if (cell.state().getBlock() instanceof PortBlock && cell.state().getValue(PortBlock.KIND) == PortBlock.Kind.ITEM_IN) {
                in = placement.at(cell);
                break;
            }
        }
        BlockState portState = in == null ? Blocks.AIR.defaultBlockState() : level.getBlockState(in);
        if (!check.ok() || c == null || in == null || !portState.hasProperty(PortBlock.FACING) || portState.getValue(PortBlock.FACING).getAxis().isVertical()) {
            expect(source, "port: the Storage Container goes down with a side input port", false, check.message().getString() + ", port " + portState);
            return;
        }
        Direction out = portState.getValue(PortBlock.FACING), along = out.getOpposite();
        BlockPos start = in.relative(out, 6), end = in.relative(out, 1);
        for (BlockPos p : new BlockPos[]{start, end}) if (level.getBlockState(p.below()).isAir()) level.setBlock(p.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        FakePlayer p = tester(level, GameType.CREATIVE, 1);
        InteractionResult r = layOnGround(level, p, start, end);
        expect(source, "port: two clicks lay a shaftless belt up to the Storage Container's input port",
            r.consumesAction() && whole(level, start, end, BeltSlope.HORIZONTAL) && level.getBlockState(start).getValue(BeltBlock.HORIZONTAL_FACING) == along,
            r + "; " + describe(level, start, end));
        int[] fed = {0};
        for (int i = 0; i < 4; i++) later(level, 40 + 20 * i, () -> {
            if (feed(level, start, along, new ItemStack(Items.COPPER_INGOT))) fed[0]++;
        });
        ControllerBlockEntity building = c;
        later(level, 360, () -> {
            int got = 0;
            for (int i = 0; i < building.input.getContainerSize(); i++) if (building.input.getItem(i).is(Items.COPPER_INGOT)) got += building.input.getItem(i).getCount();
            for (int i = 0; i < building.output.getContainerSize(); i++) if (building.output.getItem(i).is(Items.COPPER_INGOT)) got += building.output.getItem(i).getCount();
            expect(source, "port: the shaftless belt feeds the building's port (4 copper ingots in)", fed[0] == 4 && got == 4, "fed " + fed[0] + ", in the building " + got);
        });
    }

    /** Survival pays Create's one belt item for a belt, and gets just that back when the belt is broken. */
    private static void cost(CommandSourceStack source, ServerLevel level) {
        FakePlayer p = tester(level, GameType.SURVIVAL, 3);
        BlockPos a = new BlockPos(X, Y, Z + 14), b = a.east(5);
        InteractionResult r = layOnGround(level, p, a, b);
        ItemStack held = p.getMainHandItem();
        expect(source, "survival: laying a shaftless belt takes one belt item, as Create does", r.consumesAction() && whole(level, a, b, BeltSlope.HORIZONTAL)
            && held.is(AllItems.BELT_CONNECTOR) && held.getCount() == 2, r + "; " + describe(level, a, b) + "; holding " + held);
        AABB box = around(a, b);
        clearDrops(level, box);
        boolean broke = p.gameMode.destroyBlock(a);
        later(level, 2, () -> {
            int belts = dropped(level, box, AllItems.BELT_CONNECTOR), shafts = dropped(level, box, AllItems.SHAFT);
            expect(source, "survival: breaking the shaftless belt gives the belt item back and no shaft",
                broke && belts == 1 && shafts == 0, "broke " + broke + ", dropped " + belts + " belt items and " + shafts + " shafts");
            expect(source, "survival: the broken belt leaves air, no shafts, where its ends were",
                level.getBlockState(a).isAir() && level.getBlockState(b).isAir() && belts(level, cells(a, b)) == 0, level.getBlockState(a) + " / " + level.getBlockState(b));
        });
    }

    /** Too long, blocked, through a building, or not a straight line: refused, nothing laid, nothing taken. */
    private static void refusals(CommandSourceStack source, ServerLevel level) {
        int max = BeltConnectorItem.maxLength();
        FakePlayer p = tester(level, GameType.SURVIVAL, 4);
        // the longest belt Create allows (max blocks long), then one block longer
        BlockPos a = new BlockPos(X, Y, Z + 18), longest = a.east(max - 1);
        InteractionResult ok = layOnGround(level, p, a, longest);
        expect(source, "length: a " + max + "-block shaftless belt (Create's longest) goes down", ok.consumesAction() && whole(level, a, longest, BeltSlope.HORIZONTAL),
            ok + "; " + describe(level, a, longest));
        BlockPos c = new BlockPos(X, Y, Z + 20), tooFar = c.east(max);
        InteractionResult far = layOnGround(level, p, c, tooFar);
        expect(source, "length: a " + (max + 1) + "-block line is refused and nothing is laid", !far.consumesAction() && belts(level, cells(c, tooFar)) == 0,
            far + "; " + belts(level, cells(c, tooFar)) + " belt cells");

        p = tester(level, GameType.SURVIVAL, 4);
        BlockPos d = new BlockPos(X, Y, Z + 22), e = d.east(6), wall = d.east(3);
        level.setBlock(wall, Blocks.COBBLESTONE.defaultBlockState(), Block.UPDATE_ALL);
        InteractionResult blocked = layOnGround(level, p, d, e);
        expect(source, "blocked: a solid block in the line refuses the belt; the block stays and no belt item is taken",
            !blocked.consumesAction() && belts(level, cells(d, e)) == 0 && level.getBlockState(wall).is(Blocks.COBBLESTONE) && p.getMainHandItem().getCount() == 4,
            blocked + "; holding " + p.getMainHandItem());
        BlockPos f = new BlockPos(X, Y, Z + 24), g = f.east(5);
        level.setBlock(f.east(2), Blocks.SHORT_GRASS.defaultBlockState(), Block.UPDATE_ALL);
        p = tester(level, GameType.SURVIVAL, 4);
        InteractionResult grass = layOnGround(level, p, f, g);
        expect(source, "replaceable: grass in the line is no obstacle", grass.consumesAction() && whole(level, f, g, BeltSlope.HORIZONTAL), grass + "; " + describe(level, f, g));
        BlockPos h = new BlockPos(X, Y, Z + 26), diagonal = h.offset(3, 0, 2);
        p = tester(level, GameType.SURVIVAL, 4);
        InteractionResult skew = layOnGround(level, p, h, diagonal);
        expect(source, "line: two spots not in a straight belt line are refused", !skew.consumesAction() && !level.getBlockState(diagonal).is(AllBlocks.BELT), skew.toString());

        // through a building: its blocks are protected and refuse the belt
        BuildingDef def = Buildings.def("storage_container");
        BuildingTemplate t = def == null ? null : BuildingTemplate.of(def);
        if (t == null) return;
        Placement placement = new Placement(def, new BlockPos(X + 50, Y, Z + 20), Direction.NORTH, false);
        Buildings.place(level, placement, CompanyData.get(level.getServer()).byId(COMPANY), null, true);
        BlockPos member = null;
        for (BuildingTemplate.Cell cell : t.members) {
            BlockPos at = placement.at(cell);
            if (at.getY() == Y && BuildingIndex.isMember(level, at)) {
                member = at;
                break;
            }
        }
        if (member == null) {
            expect(source, "building: the Storage Container has a block at ground level", false, "");
            return;
        }
        // the line runs from open ground west of the building, eastward through it
        BlockPos from = member.west();
        while (BuildingIndex.isMember(level, from) && from.getX() > member.getX() - 30) from = from.west();
        from = from.west();
        BlockPos to = from.east(Math.min(max - 1, 19));
        BlockState before = level.getBlockState(member);
        p = tester(level, GameType.SURVIVAL, 4);
        InteractionResult through = layOnGround(level, p, from, to);
        expect(source, "building: a belt line through a building's blocks is refused and the building is untouched",
            !through.consumesAction() && belts(level, cells(from, to)) == 0 && level.getBlockState(member) == before && BuildingIndex.isMember(level, member),
            through + " from " + from.toShortString() + " to " + to.toShortString() + " across " + member.toShortString());
    }

    /**
     * Two shafts clicked with the belt item, the old way: the belt is exactly what Create lays between them, cell for
     * cell, its ends keep their pulleys, and breaking an end gives Create's drops (the belt item and that end's shaft)
     * and leaves the other shaft standing.
     */
    private static void oldWay(CommandSourceStack source, ServerLevel level) {
        BlockPos a = new BlockPos(X, Y, Z + 30), b = a.east(6), ra = a.south(2), rb = b.south(2);
        for (BlockPos s : new BlockPos[]{a, b, ra, rb}) shaft(level, s, Direction.Axis.Z);
        FakePlayer p = tester(level, GameType.SURVIVAL, 2);
        click(level, p, a, Direction.UP);
        InteractionResult r = click(level, p, b, Direction.UP);
        BeltConnectorItem.createBelts(level, ra, rb);
        boolean same = true;
        List<BlockPos> clicked = cells(a, b), direct = cells(ra, rb);
        for (int i = 0; i < clicked.size(); i++) same &= level.getBlockState(clicked.get(i)) == level.getBlockState(direct.get(i));
        BeltBlockEntity x = beltAt(level, a), y = beltAt(level, b);
        expect(source, "old way: clicking two shafts still lays Create's belt, the same cell for cell as Create's own",
            r.consumesAction() && whole(level, a, b, BeltSlope.HORIZONTAL) && same && p.getMainHandItem().getCount() == 1, r + "; " + describe(level, a, b));
        expect(source, "old way: its ends keep their pulleys", x != null && y != null && x.hasPulley() && y.hasPulley(), "");
        AABB box = around(a, b);
        clearDrops(level, box);
        boolean broke = p.gameMode.destroyBlock(a);
        later(level, 2, () -> {
            int belts = dropped(level, box, AllItems.BELT_CONNECTOR), shafts = dropped(level, box, AllItems.SHAFT);
            expect(source, "old way: breaking an end gives the belt item and that end's shaft, and the far shaft stands again",
                broke && belts == 1 && shafts == 1 && level.getBlockState(b).is(AllBlocks.SHAFT), "dropped " + belts + " belt items, " + shafts + " shafts; far end " + level.getBlockState(b));
        });
    }

    // ------------------------------------------------------------------------------------------------ after a restart

    private static int restart(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        REPORT.reset();
        expect(source, "restart: the shaftless flat belt is still there, whole", whole(level, FLAT_A, FLAT_B, BeltSlope.HORIZONTAL), describe(level, FLAT_A, FLAT_B));
        expect(source, "restart: its ends still have no pulley", noPulleys(level, FLAT_A, FLAT_B), "");
        Company c = CompanyData.get(level.getServer()).byId(COMPANY);
        int perMinute = SelfMovingBelts.itemsPerMinute(c);
        speed(source, level, "restart: the shaftless flat belt", FLAT_A, Direction.EAST, perMinute);
        later(level, 80, () -> report(source, "restart done: " + REPORT.totals()));
        return 1;
    }

    // ------------------------------------------------------------------------------------------------ the client part

    /** Ground for the client's belt, the line clear, and the test company's belt speed for whoever lays it. */
    private static int clientSetup(CommandSourceStack source) {
        ServerLevel level = source.getServer().overworld();
        CLIENT.reset();
        BuildingTests.platform(level, CLIENT_A.getX() - 6, CLIENT_A.getZ() - 6, CLIENT_B.getX() + 6, CLIENT_A.getZ() + 8, Y, 8);
        CLIENT.report(source, "spot ready at " + CLIENT_A.toShortString());
        return 1;
    }

    /** Puts a block in the client belt's line (the preview goes red), or takes it away (green). */
    private static int clientBlock(CommandSourceStack source, boolean put) {
        ServerLevel level = source.getServer().overworld();
        level.setBlock(CLIENT_BLOCK, put ? Blocks.COBBLESTONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        CLIENT.report(source, (put ? "block put in" : "block taken out of") + " the line at " + CLIENT_BLOCK.toShortString());
        return 1;
    }

    /** What the real client's two clicks laid: a shaftless belt from the first spot to the second. */
    private static int clientCheck(CommandSourceStack source) {
        ServerLevel level = source.getServer().overworld();
        ServerPlayer player = null;
        for (ServerPlayer p : source.getServer().getPlayerList().getPlayers()) if (!(p instanceof FakePlayer)) player = p;
        CLIENT.expect(source, "the client's two clicks on plain ground laid a shaftless belt", whole(level, CLIENT_A, CLIENT_B, BeltSlope.HORIZONTAL)
            && noPulleys(level, CLIENT_A, CLIENT_B), describe(level, CLIENT_A, CLIENT_B));
        ItemStack held = player == null ? ItemStack.EMPTY : player.getMainHandItem();
        CLIENT.expect(source, "the client's belt item has no first end left on it", held.is(AllItems.BELT_CONNECTOR) && !held.has(AllDataComponents.BELT_FIRST_SHAFT), held.toString());
        CLIENT.report(source, "done: " + CLIENT.totals());
        return 1;
    }
}
