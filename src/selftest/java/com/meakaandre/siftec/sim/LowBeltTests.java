package com.meakaandre.siftec.sim;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.TestQueue;
import com.meakaandre.siftec.TestReport;
import com.meakaandre.siftec.building.BuildingTests;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.company.CompanyData;
import com.meakaandre.siftec.hub.Milestone;
import com.meakaandre.siftec.hub.Milestones;
import com.meakaandre.siftec.owner.Ownership;
import com.meakaandre.siftec.tweak.SelfMovingBelts;
import com.meakaandre.siftec.tweak.ShaftlessBelts;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.context.CommandContext;
import com.zurrtum.create.AllBlocks;
import com.zurrtum.create.AllItems;
import com.zurrtum.create.api.behaviour.BlockEntityBehaviour;
import com.zurrtum.create.content.kinetics.belt.BeltBlockEntity;
import com.zurrtum.create.content.kinetics.belt.behaviour.DirectBeltInputBehaviour;
import com.zurrtum.create.content.kinetics.belt.transport.TransportedItemStack;
import com.zurrtum.create.content.logistics.funnel.BeltFunnelBlock;
import com.zurrtum.create.content.logistics.tunnel.BeltTunnelBlock;
import com.zurrtum.create.content.logistics.tunnel.BeltTunnelBlockEntity;
import com.zurrtum.create.content.processing.basin.BasinBlockEntity;
import com.zurrtum.create.foundation.blockEntity.behaviour.filtering.ServerFilteringBehaviour;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Selftests for one-block belts, run by the smoke test: {@code /siftec selftest lowbelts} (and {@code lowbelts restart}
 * after the restart). Funnels and tunnels clip into the belt's own block (placing one, refusing one in the block
 * above, one per block), each built-in one does what Create's stacked one does side by side (the same items, splits and
 * filters), a line with a brass tunnel and a funnel feeds a Basin under a solid ceiling one block above the floor,
 * vertical belts lift and lower items, an entity rides a belt, the belt's Mk follows the milestone speed, the wrench
 * turns and takes off an attachment, a broken belt drops its attachment, and the simulation's tracer reads a built-in
 * tunnel and funnel as it reads Create's. Every check prints "SELFTEST lowbelts"; failures say FAIL.
 */
public final class LowBeltTests {
    private static final int X = 17000, Y = 200, Z = -17000;
    private static final UUID TESTER = UUID.fromString("10b4e175-be17-4a1d-9000-0000000010b1");
    private static final String COMPANY = "selftest_lowbelts";
    /** The Basin line under the ceiling: its start, its end (with the funnel), the Basin, and the brass tunnel's block. */
    static final BlockPos BASIN_A = new BlockPos(X, Y, Z + 40), BASIN_B = BASIN_A.east(5), BASIN = BASIN_B.east(), BASIN_TUNNEL = BASIN_A.east(2);

    private static final TestQueue LATER = new TestQueue();
    private static final TestReport REPORT = new TestReport("SELFTEST lowbelts ");

    private LowBeltTests() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> LATER.runDue(server.overworld().getGameTime()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> LATER.clear());
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
            Commands.literal("siftec").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("selftest").then(Commands.literal("lowbelts").executes(LowBeltTests::run)
                    .then(Commands.literal("restart").executes(LowBeltTests::restart))))
                .then(Commands.literal("worldtest").then(Commands.literal("lowbelts").then(Commands.literal("feed").executes(c -> feedForShots(c.getSource())))))));
    }

    // ------------------------------------------------------------------------------------------------ reaching the new code

    /**
     * The one-block belt's code, reached by name: these selftests also run against a build without it (to show what it
     * changes), where every attachment reads as none.
     */
    public static final class Probe {
        private static @Nullable Object call(String owner, String name, Class<?>[] types, Object... args) {
            try {
                Method m = Class.forName("com.meakaandre.siftec.belt." + owner).getMethod(name, types);
                return m.invoke(null, args);
            } catch (ReflectiveOperationException | LinkageError e) {
                return null;
            }
        }

        /** The belt block's built-in funnel or tunnel, or null. */
        public static @Nullable BlockState attached(ServerLevel level, BlockPos pos) {
            return level.getBlockEntity(pos) instanceof BeltBlockEntity belt
                ? (BlockState) call("BeltAttachments", "stateOf", new Class<?>[]{BeltBlockEntity.class}, belt) : null;
        }

        /** The block entity of the belt block's built-in attachment, or null. */
        static @Nullable Object attachment(ServerLevel level, BlockPos pos) {
            return level.getBlockEntity(pos) instanceof BeltBlockEntity belt
                ? call("BeltAttachments", "of", new Class<?>[]{BeltBlockEntity.class}, belt) : null;
        }

        /** Leaves the stacked funnel or tunnel above this belt block where it is (the comparison line). */
        @SuppressWarnings("unchecked")
        static void keepStacked(BlockPos pos) {
            try {
                ((Set<BlockPos>) Class.forName("com.meakaandre.siftec.belt.BeltAttachments").getField("KEEP_STACKED").get(null)).add(pos.immutable());
            } catch (ReflectiveOperationException | LinkageError e) {
                Siftec.LOGGER.debug("SIFTEC: no built-in belt attachments in this build", e);
            }
        }

        static int mk(BeltBlockEntity belt) {
            Object mk = call("BeltLook", "mk", new Class<?>[]{BeltBlockEntity.class}, belt);
            return mk instanceof Integer i ? i : -1;
        }

        static String trim(int mk) {
            Object trim = call("BeltLook", "trim", new Class<?>[]{int.class}, mk);
            return String.valueOf(trim);
        }
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

    private static List<String> beltTokens() {
        List<String> out = new ArrayList<>();
        for (Milestone m : Milestones.all()) for (String t : m.tokens()) if (t.startsWith("belt:") && !out.contains(t)) out.add(t);
        out.sort((a, b) -> Integer.compare(Integer.parseInt(a.substring(5)), Integer.parseInt(b.substring(5))));
        return out;
    }

    /** A test company (made afresh) that has done the milestones carrying this belt token; the tester owns the main one. */
    private static String company(ServerLevel level, String id, String token, boolean tester) {
        CompanyData data = CompanyData.get(level.getServer());
        Company c = new Company();
        c.id = id;
        c.name = id;
        if (tester) {
            c.members.add(TESTER.toString());
            c.owner = TESTER.toString();
            // the milestones that unlock the funnels and tunnels, or survival is refused them (SIFTEC's locks)
            for (Item item : new Item[]{AllItems.ANDESITE_TUNNEL, AllItems.BRASS_TUNNEL, AllItems.ANDESITE_FUNNEL, AllItems.BRASS_FUNNEL}) {
                Milestone unlock = com.meakaandre.siftec.hub.Locks.lockOf(item);
                if (unlock != null) c.done.add(unlock.id());
            }
        }
        for (Milestone m : Milestones.all()) if (m.tokens().contains(token)) c.done.add(m.id());
        data.companies().put(id, c);
        data.setDirty();
        return id;
    }

    private static FakePlayer tester(ServerLevel level, GameType mode, ItemStack held) {
        FakePlayer p = FakePlayer.get(level, new GameProfile(TESTER, "selftest_lowbelts"));
        p.getInventory().clearContent();
        p.setShiftKeyDown(false);
        p.setGameMode(mode);
        p.setYRot(0);
        p.setItemInHand(InteractionHand.MAIN_HAND, held);
        return p;
    }

    private static InteractionResult click(ServerLevel level, FakePlayer p, BlockPos block, Direction face) {
        for (int i = 0; i < 6; i++) p.getCooldowns().tick();
        p.setPos(block.getX() + 0.5, block.getY() + 1, block.getZ() - 1.5);
        Vec3 hit = Vec3.atCenterOf(block).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        return p.gameMode.useItemOn(p, level, p.getMainHandItem(), InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, block, false));
    }

    /** A shaftless belt from a to b, owned by a company (so it runs at that company's milestone speed). */
    private static void belt(ServerLevel level, BlockPos a, BlockPos b, String company) {
        Direction.Axis axis = ShaftlessBelts.axis(level, a, b, Direction.NORTH);
        if (axis == null) return;
        ShaftlessBelts.lay(level, a, b, axis);
        BlockPos step = new BlockPos(Integer.signum(b.getX() - a.getX()), Integer.signum(b.getY() - a.getY()), Integer.signum(b.getZ() - a.getZ()));
        for (BlockPos at = a; ; at = at.offset(step)) {
            if (level.getBlockEntity(at) instanceof BeltBlockEntity be) {
                be.setAttached(Ownership.OWNER, company);
                SelfMovingBelts.recheck(be);
            }
            if (at.equals(b)) break;
        }
    }

    private static @Nullable BeltBlockEntity beltAt(ServerLevel level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof BeltBlockEntity b ? b : null;
    }

    private static boolean feed(ServerLevel level, BlockPos start, Direction along, ItemStack one) {
        DirectBeltInputBehaviour in = BlockEntityBehaviour.get(level, start, DirectBeltInputBehaviour.TYPE);
        if (in == null || !in.canInsertFromSide(along)) return false;
        return in.handleInsertion(one.copy(), along, false).isEmpty();
    }

    /** Feeds the belt's start one item every {@code every} ticks from {@code from} on, the items in turn. */
    private static void feeder(ServerLevel level, BlockPos start, Direction along, List<Item> items, int from, int every, int[] fed) {
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            later(level, from + every * i, () -> {
                if (feed(level, start, along, new ItemStack(item))) fed[0]++;
            });
        }
    }

    private static int onGround(ServerLevel level, BlockPos near, double radius, Item item) {
        int n = 0;
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, new AABB(near).inflate(radius))) if (e.getItem().is(item)) n += e.getItem().getCount();
        return n;
    }

    private static int onBelt(ServerLevel level, BlockPos anySegment, @Nullable Item item) {
        BeltBlockEntity b = beltAt(level, anySegment);
        BeltBlockEntity c = b == null ? null : b.getControllerBE();
        int n = 0;
        if (c != null && c.getInventory() != null) for (TransportedItemStack t : c.getInventory().getTransportedItems()) if (item == null || t.stack.is(item)) n += t.stack.getCount();
        return n;
    }

    private static long stored(ServerLevel level, BlockPos pos, Item item) {
        if (level.getBlockEntity(pos) instanceof BasinBlockEntity basin) {
            long n = 0;
            for (int i = 0; i < basin.itemCapability.getContainerSize(); i++) if (basin.itemCapability.getItem(i).is(item)) n += basin.itemCapability.getItem(i).getCount();
            return n;
        }
        Storage<ItemVariant> s = ItemStorage.SIDED.find(level, pos, null);
        long n = 0;
        if (s != null) for (StorageView<ItemVariant> v : s) if (!v.isResourceBlank() && v.getResource().isOf(item)) n += v.getAmount();
        return n;
    }

    private static String describe(@Nullable BlockState state) {
        return state == null ? "none" : state.toString();
    }

    private static List<Item> repeat(int n, Item... items) {
        List<Item> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.addAll(List.of(items));
        return out;
    }

    // ------------------------------------------------------------------------------------------------ the test

    private static int run(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        REPORT.reset();
        BuildingTests.platform(level, X - 6, Z - 6, X + 100, Z + 70, Y, 12);
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, new AABB(X - 6, Y - 2, Z - 6, X + 100, Y + 12, Z + 70))) e.discard();
        for (ArmorStand e : level.getEntitiesOfClass(ArmorStand.class, new AABB(X - 6, Y - 2, Z - 6, X + 100, Y + 12, Z + 70))) e.discard();
        List<String> tokens = beltTokens();
        String token = tokens.size() > 1 ? tokens.get(1) : "belt:120";
        String main = company(level, COMPANY, token, true);
        REPORT.attempt(source, "placing", () -> placing(source, level, main));
        REPORT.attempt(source, "tunnels side by side", () -> tunnels(source, level, main));
        REPORT.attempt(source, "funnels side by side", () -> funnels(source, level, main));
        REPORT.attempt(source, "basin under a ceiling", () -> basin(source, level, main));
        REPORT.attempt(source, "vertical", () -> vertical(source, level, main));
        REPORT.attempt(source, "riding", () -> riding(source, level, main));
        REPORT.attempt(source, "mk", () -> mk(source, level, tokens));
        REPORT.attempt(source, "wrench and breaking", () -> wrench(source, level, main));
        later(level, 700, () -> report(source, "done: " + REPORT.totals()));
        report(source, "test site ready; the checks report over the next thirty-five seconds");
        return 1;
    }

    /**
     * Each of Create's four belt attachments clipped in with a click on the belt; one per belt block; a funnel put into
     * the block above a belt (by a block placement or by a player clicking a block beside that cell) ends up built in.
     */
    private static void placing(CommandSourceStack source, ServerLevel level, String company) {
        Item[] items = {AllItems.ANDESITE_TUNNEL, AllItems.BRASS_TUNNEL, AllItems.ANDESITE_FUNNEL, AllItems.BRASS_FUNNEL};
        Block[] blocks = {AllBlocks.ANDESITE_TUNNEL, AllBlocks.BRASS_TUNNEL, AllBlocks.ANDESITE_BELT_FUNNEL, AllBlocks.BRASS_BELT_FUNNEL};
        for (int i = 0; i < items.length; i++) {
            BlockPos a = new BlockPos(X, Y, Z + 3 * i), b = a.east(4), at = a.east(2);
            belt(level, a, b, company);
            FakePlayer p = tester(level, GameType.SURVIVAL, new ItemStack(items[i], 2));
            InteractionResult r = click(level, p, at, Direction.UP);
            BlockState state = Probe.attached(level, at);
            expect(source, "place: a click on a belt block with " + items[i] + " clips it into the belt; the block above stays empty",
                r.consumesAction() && state != null && state.is(blocks[i]) && level.getBlockState(at.above()).isAir() && p.getMainHandItem().getCount() == 1,
                r + "; built in " + describe(state) + ", above " + level.getBlockState(at.above()) + ", holding " + p.getMainHandItem());
            if (i == 2) {
                // a funnel clicked on the top faces the clicking player (who looks south here)
                expect(source, "place: a funnel clicked on the belt's top faces the player, as Create places one",
                    state != null && state.getValue(BeltFunnelBlock.HORIZONTAL_FACING) == Direction.NORTH, describe(state));
                InteractionResult again = click(level, p, at, Direction.UP);
                expect(source, "place: one attachment per belt block: a second funnel on it is refused", !again.consumesAction()
                    && p.getMainHandItem().getCount() == 1 && level.getBlockState(at.above()).isAir(), again + ", holding " + p.getMainHandItem());
            }
        }
        // the block above a belt takes no funnel or tunnel: one put there by any means is taken into the belt
        BlockPos a = new BlockPos(X, Y, Z + 12), b = a.east(4), at = a.east(1), at2 = a.east(3);
        belt(level, a, b, company);
        level.setBlock(at.above(), AllBlocks.ANDESITE_BELT_FUNNEL.defaultBlockState().setValue(BeltFunnelBlock.HORIZONTAL_FACING, Direction.NORTH), Block.UPDATE_ALL);
        level.setBlock(at2.above().south(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        FakePlayer p = tester(level, GameType.CREATIVE, new ItemStack(AllItems.ANDESITE_FUNNEL));
        click(level, p, at2.above().south(), Direction.NORTH);
        later(level, 5, () -> {
            expect(source, "stacked: a funnel put into the block above a belt is taken into the belt (built in only)",
                level.getBlockState(at.above()).isAir() && Probe.attached(level, at) != null, "above " + level.getBlockState(at.above()) + ", built in " + describe(Probe.attached(level, at)));
            expect(source, "stacked: a funnel a player places into the block above a belt (from the block beside it) ends up built in",
                level.getBlockState(at2.above()).isAir() && Probe.attached(level, at2) != null, "above " + level.getBlockState(at2.above()) + ", built in " + describe(Probe.attached(level, at2)));
        });
    }

    /**
     * An andesite and a brass tunnel over a junction (a side belt running off), built in on one line and stacked (Create's
     * own) on a twin line: the same items go straight on and to the side on both.
     */
    private static void tunnels(CommandSourceStack source, ServerLevel level, String company) {
        Item[] items = {AllItems.ANDESITE_TUNNEL, AllItems.BRASS_TUNNEL};
        Block[] blocks = {AllBlocks.ANDESITE_TUNNEL, AllBlocks.BRASS_TUNNEL};
        for (int k = 0; k < items.length; k++) {
            int[][] counts = new int[2][];
            for (int line = 0; line < 2; line++) {
                boolean builtIn = line == 0;
                BlockPos a = new BlockPos(X + 20 + 24 * k + 12 * line, Y, Z), b = a.east(7), at = a.east(3), side = at.south(), sideEnd = side.south(3);
                belt(level, a, b, company);
                belt(level, side, sideEnd, company);
                if (builtIn) {
                    click(level, tester(level, GameType.CREATIVE, new ItemStack(items[k])), at, Direction.UP);
                } else {
                    Probe.keepStacked(at);
                    if (beltAt(level, at) instanceof BeltBlockEntity belt) belt.setCasingType(k == 0 ? BeltBlockEntity.CasingType.ANDESITE : BeltBlockEntity.CasingType.BRASS);
                    level.setBlock(at.above(), blocks[k].defaultBlockState(), Block.UPDATE_ALL);
                    if (blocks[k] instanceof BeltTunnelBlock t) t.updateTunnel(level, at.above());
                    if (level.getBlockEntity(at.above()) instanceof BeltTunnelBlockEntity be) be.updateTunnelConnections();
                }
                int[] fed = {0};
                feeder(level, a, Direction.EAST, repeat(8, Items.IRON_INGOT), 40, 12, fed);
                int idx = line;
                later(level, 420, () -> counts[idx] = new int[]{fed[0], onGround(level, b.east(), 1.5, Items.IRON_INGOT) + onBelt(level, b, Items.IRON_INGOT),
                    onGround(level, sideEnd.south(), 1.5, Items.IRON_INGOT) + onBelt(level, side, Items.IRON_INGOT)});
            }
            String name = items[k].toString();
            boolean brass = k == 1;
            later(level, 425, () -> {
                int[] in = counts[0], st = counts[1];
                expect(source, "tunnel: a built-in " + name + " splits like Create's stacked one (fed, straight on, to the side)",
                    in != null && st != null && in[0] == 8 && java.util.Arrays.equals(in, st) && (brass || in[2] > 0),
                    "built in " + java.util.Arrays.toString(in) + ", stacked " + java.util.Arrays.toString(st));
            });
        }
    }

    /**
     * An andesite and a brass funnel at a belt's end, built in (feeding a chest beside the belt's end at its height) and
     * stacked (Create's own, feeding a chest a block up): the same items go in; the brass one's filter holds back the same.
     */
    private static void funnels(CommandSourceStack source, ServerLevel level, String company) {
        Item[] items = {AllItems.ANDESITE_FUNNEL, AllItems.BRASS_FUNNEL};
        Block[] blocks = {AllBlocks.ANDESITE_BELT_FUNNEL, AllBlocks.BRASS_BELT_FUNNEL};
        for (int k = 0; k < items.length; k++) {
            long[][] counts = new long[2][];
            boolean brass = k == 1;
            for (int line = 0; line < 2; line++) {
                boolean builtIn = line == 0;
                BlockPos a = new BlockPos(X + 20 + 24 * k + 12 * line, Y, Z + 12), b = a.east(5);
                BlockPos chest = builtIn ? b.east() : b.east().above();
                if (!builtIn) level.setBlock(b.east(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                level.setBlock(chest, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
                belt(level, a, b, company);
                BlockState funnel = null;
                if (builtIn) {
                    click(level, tester(level, GameType.CREATIVE, new ItemStack(items[k])), b, Direction.WEST);
                    funnel = Probe.attached(level, b);
                } else {
                    Probe.keepStacked(b);
                    level.setBlock(b.above(), blocks[k].defaultBlockState().setValue(BeltFunnelBlock.HORIZONTAL_FACING, Direction.WEST)
                        .setValue(BeltFunnelBlock.SHAPE, BeltFunnelBlock.getShapeForPosition(level, b.above(), Direction.WEST, true)), Block.UPDATE_ALL);
                    funnel = level.getBlockState(b.above());
                }
                if (brass) {
                    ServerFilteringBehaviour filter = builtIn ? BlockEntityBehaviour.get(level, b, ServerFilteringBehaviour.TYPE)
                        : BlockEntityBehaviour.get(level, b.above(), ServerFilteringBehaviour.TYPE);
                    if (filter != null) filter.setFilter(new ItemStack(Items.IRON_INGOT));
                    else expect(source, "funnel: the " + (builtIn ? "built-in" : "stacked") + " brass funnel's filter is reached", false, describe(funnel));
                }
                int[] fed = {0};
                feeder(level, a, Direction.EAST, brass ? repeat(3, Items.IRON_INGOT, Items.COPPER_INGOT) : repeat(6, Items.IRON_INGOT), 40, 12, fed);
                int idx = line;
                BlockState shown = funnel;
                later(level, 360, () -> counts[idx] = new long[]{fed[0], stored(level, chest, Items.IRON_INGOT), stored(level, chest, Items.COPPER_INGOT),
                    onBelt(level, b, null), shown == null ? -1 : shown.getValue(BeltFunnelBlock.SHAPE).ordinal()});
            }
            String name = items[k].toString();
            later(level, 365, () -> {
                long[] in = counts[0], st = counts[1];
                expect(source, "funnel: a built-in " + name + " at a belt's end feeds the block beside it like Create's stacked one (fed, iron in, copper in, left on the belt, shape)",
                    in != null && st != null && java.util.Arrays.equals(in, st) && in[1] > 0, "built in " + java.util.Arrays.toString(in) + ", stacked " + java.util.Arrays.toString(st));
            });
        }
    }

    /**
     * A belt line with a built-in brass tunnel and a brass funnel at its end feeds a Basin beside the end, all under a solid
     * ceiling one block above the floor (nothing in the line is more than a block high).
     */
    private static void basin(CommandSourceStack source, ServerLevel level, String company) {
        level.setBlock(BASIN, AllBlocks.BASIN.defaultBlockState(), Block.UPDATE_ALL);
        belt(level, BASIN_A, BASIN_B, company);
        for (int x = BASIN_A.getX() - 1; x <= BASIN.getX() + 1; x++)
            for (int z = BASIN_A.getZ() - 1; z <= BASIN_A.getZ() + 1; z++) level.setBlock(new BlockPos(x, Y + 1, z), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        click(level, tester(level, GameType.CREATIVE, new ItemStack(AllItems.BRASS_TUNNEL)), BASIN_TUNNEL, Direction.UP);
        click(level, tester(level, GameType.CREATIVE, new ItemStack(AllItems.BRASS_FUNNEL)), BASIN_B, Direction.WEST);
        BlockState tunnel = Probe.attached(level, BASIN_TUNNEL), funnel = Probe.attached(level, BASIN_B);
        expect(source, "basin: under a solid ceiling a block up, a brass tunnel and a brass funnel clip into the belt",
            tunnel != null && tunnel.is(AllBlocks.BRASS_TUNNEL) && funnel != null && funnel.is(AllBlocks.BRASS_BELT_FUNNEL), describe(tunnel) + " / " + describe(funnel));
        int[] fed = {0};
        feeder(level, BASIN_A, Direction.EAST, repeat(4, Items.IRON_INGOT), 40, 12, fed);
        later(level, 300, () -> {
            boolean ceiling = true;
            for (int x = BASIN_A.getX(); x <= BASIN.getX(); x++) ceiling &= level.getBlockState(new BlockPos(x, Y + 1, BASIN_A.getZ())).is(Blocks.STONE);
            long in = stored(level, BASIN, Items.IRON_INGOT);
            expect(source, "basin: the line feeds the Basin beside its end through the built-in funnel, the ceiling untouched",
                fed[0] == 4 && in == 4 && ceiling, "fed " + fed[0] + ", in the Basin " + in + ", on the belt " + onBelt(level, BASIN_A, null) + ", ceiling whole " + ceiling);
            // the funnel's filter for the restart check: from now on only iron goes in
            ServerFilteringBehaviour filter = BlockEntityBehaviour.get(level, BASIN_B, ServerFilteringBehaviour.TYPE);
            if (filter != null) filter.setFilter(new ItemStack(Items.IRON_INGOT));
        });
    }

    /**
     * A vertical belt lifts items into a Depot above its top, another lowers them into a Depot below its foot, and a belt
     * running into a vertical belt's foot hands its items up.
     */
    private static void vertical(CommandSourceStack source, ServerLevel level, String company) {
        BlockPos up = new BlockPos(X + 70, Y, Z), top = up.above(4);
        BlockPos down = new BlockPos(X + 74, Y + 5, Z), bottom = down.below(4);
        level.setBlock(top.above(), AllBlocks.DEPOT.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(bottom.below(), AllBlocks.DEPOT.defaultBlockState(), Block.UPDATE_ALL);
        belt(level, up, top, company);
        belt(level, down, bottom, company);
        // one gold ingot on each, put on as soon as the belt has its inventory (a belt sets itself up over its first ticks)
        boolean[] added = new boolean[2];
        for (int t = 20; t <= 80; t += 5) later(level, t, () -> {
            BlockPos[] starts = {up, down};
            for (int i = 0; i < 2; i++) {
                if (added[i]) continue;
                BeltBlockEntity b = beltAt(level, starts[i]);
                BeltBlockEntity c = b == null ? null : b.getControllerBE();
                if (c == null || c.getInventory() == null || c.beltLength == 0) continue;
                TransportedItemStack item = new TransportedItemStack(new ItemStack(Items.GOLD_INGOT));
                item.beltPosition = 0.5f;
                c.getInventory().addItem(item);
                added[i] = true;
            }
        });
        // where the lowered ingot is, every 10 ticks (a log line, not a check)
        for (int t = 30; t <= 150; t += 10) {
            int at = t;
            later(level, t, () -> {
                BeltBlockEntity b = beltAt(level, down), c = b == null ? null : b.getControllerBE();
                StringBuilder where = new StringBuilder();
                if (c != null && c.getInventory() != null) for (TransportedItemStack i : c.getInventory().getTransportedItems()) where.append(i.stack).append('@').append(i.beltPosition).append(' ');
                report(source, "vertical trace t" + at + ": on the belt [" + where + "], Depot " + stored(level, bottom.below(), Items.GOLD_INGOT)
                    + ", loose " + onGround(level, down.below(2), 8, Items.GOLD_INGOT));
            });
        }
        later(level, 160, () -> {
            long lifted = stored(level, top.above(), Items.GOLD_INGOT), lowered = stored(level, bottom.below(), Items.GOLD_INGOT);
            expect(source, "vertical: a vertical belt lifts an item into the Depot above its top", lifted == 1, "in the Depot " + lifted + ", on the belt " + onBelt(level, up, null));
            BeltBlockEntity db = beltAt(level, down), dc = db == null ? null : db.getControllerBE();
            String how = dc == null ? "no controller" : "controller " + dc.getBlockPos().toShortString() + " facing " + dc.getBeltFacing()
                + ", moving " + dc.getMovementFacing() + " at " + dc.getDirectionAwareBeltMovementSpeed() + ", length " + dc.beltLength;
            expect(source, "vertical: a vertical belt lowers an item into the Depot below its foot", lowered == 1, "in the Depot " + lowered + ", on the belt "
                + onBelt(level, down, null) + ", put on " + added[1] + ", loose " + onGround(level, down.below(2), 5, Items.GOLD_INGOT) + "; " + how);
        });
        // a flat belt running into the foot of a vertical one: its items go up
        BlockPos foot = new BlockPos(X + 78, Y, Z), head = foot.above(4), from = foot.west(4), to = foot.west();
        level.setBlock(head.above(), AllBlocks.DEPOT.defaultBlockState(), Block.UPDATE_ALL);
        belt(level, foot, head, company);
        belt(level, from, to, company);
        int[] fed = {0};
        feeder(level, from, Direction.EAST, repeat(1, Items.EMERALD), 40, 20, fed);
        later(level, 300, () -> {
            long topped = stored(level, head.above(), Items.EMERALD);
            expect(source, "vertical: a belt running into a vertical belt's foot hands its items up", fed[0] == 1 && topped == 1,
                "fed " + fed[0] + ", in the Depot " + topped + ", on the flat belt " + onBelt(level, from, null) + ", on the vertical " + onBelt(level, foot, null));
        });
    }

    /** An entity standing on a flat belt (on its 5 px rails) is carried along. */
    private static void riding(CommandSourceStack source, ServerLevel level, String company) {
        BlockPos a = new BlockPos(X + 70, Y, Z + 12), b = a.east(10);
        belt(level, a, b, company);
        ArmorStand stand = EntityTypes.ARMOR_STAND.create(level, EntitySpawnReason.COMMAND);
        if (stand == null) {
            expect(source, "riding: an armor stand can be made", false, "");
            return;
        }
        stand.setPos(a.getX() + 1.5, Y + 5 / 16.0, a.getZ() + 0.5);
        level.addFreshEntity(stand);
        double x0 = stand.getX();
        later(level, 60, () -> expect(source, "riding: an entity standing on a belt's rails is carried along it", stand.getX() - x0 > 1.5,
            String.format(Locale.ROOT, "moved %.2f blocks east, now at y %.3f", stand.getX() - x0, stand.getY() - Y)));
    }

    /** One belt per belt Mk, each owned by a company with that Mk: the belt reads as its Mk, with the Mk's trim. */
    private static void mk(CommandSourceStack source, ServerLevel level, List<String> tokens) {
        String[] trims = {"ANDESITE", "ANDESITE", "COPPER", "COPPER", "BRASS", "STEEL"};
        for (int i = 0; i < tokens.size(); i++) {
            String owner = company(level, "selftest_lowbelts_mk" + (i + 1), tokens.get(i), false);
            BlockPos a = new BlockPos(X + 70, Y, Z + 20 + 2 * i), b = a.east(5);
            belt(level, a, b, owner);
            int mk = i + 1;
            later(level, 40, () -> {
                BeltBlockEntity belt = beltAt(level, a);
                int read = belt == null ? -1 : Probe.mk(belt);
                String trim = Probe.trim(mk);
                expect(source, "mk: a belt of a company with Belt Mk." + mk + " runs as Mk." + mk + " with " + trims[mk - 1] + " trim",
                    read == mk && trims[mk - 1].equals(trim), "reads Mk." + read + ", trim " + trim);
            });
        }
    }

    /**
     * The wrench works a built-in funnel as Create's own (turning it), sneak-wrenching takes it off into a survival
     * player's inventory with the belt left, and breaking a belt block drops the belt item and its attachment.
     */
    private static void wrench(CommandSourceStack source, ServerLevel level, String company) {
        BlockPos a = new BlockPos(X + 70, Y, Z + 40), b = a.east(4);
        belt(level, a, b, company);
        click(level, tester(level, GameType.CREATIVE, new ItemStack(AllItems.ANDESITE_FUNNEL)), b, Direction.WEST);
        BlockState before = Probe.attached(level, b);
        FakePlayer p = tester(level, GameType.SURVIVAL, new ItemStack(AllItems.WRENCH));
        click(level, p, b, Direction.UP);
        BlockState turned = Probe.attached(level, b);
        expect(source, "wrench: the wrench turns a built-in funnel as Create's own (retracted to extended)",
            before != null && turned != null && before.getValue(BeltFunnelBlock.SHAPE) == BeltFunnelBlock.Shape.RETRACTED
                && turned.getValue(BeltFunnelBlock.SHAPE) == BeltFunnelBlock.Shape.EXTENDED, describe(before) + " -> " + describe(turned));
        p.setShiftKeyDown(true);
        click(level, p, b, Direction.UP);
        p.setShiftKeyDown(false);
        int got = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) if (p.getInventory().getItem(i).is(AllItems.ANDESITE_FUNNEL)) got += p.getInventory().getItem(i).getCount();
        expect(source, "wrench: sneak-wrenching takes a built-in funnel off into the player's inventory; the belt stays",
            Probe.attached(level, b) == null && got == 1 && level.getBlockState(b).is(AllBlocks.BELT), "attached " + describe(Probe.attached(level, b)) + ", got " + got);

        BlockPos c = new BlockPos(X + 70, Y, Z + 44), d = c.east(4), mid = c.east(2);
        belt(level, c, d, company);
        click(level, tester(level, GameType.CREATIVE, new ItemStack(AllItems.BRASS_TUNNEL)), mid, Direction.UP);
        AABB box = new AABB(c).minmax(new AABB(d)).inflate(2);
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, box)) e.discard();
        FakePlayer breaker = tester(level, GameType.SURVIVAL, ItemStack.EMPTY);
        boolean broke = breaker.gameMode.destroyBlock(mid);
        later(level, 3, () -> {
            int belts = 0, tunnels = 0;
            for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, box)) {
                if (e.getItem().is(AllItems.BELT_CONNECTOR)) belts += e.getItem().getCount();
                if (e.getItem().is(AllItems.BRASS_TUNNEL)) tunnels += e.getItem().getCount();
            }
            expect(source, "break: breaking a belt block drops the belt item and its built-in tunnel", broke && belts == 1 && tunnels == 1,
                "broke " + broke + ", belt items " + belts + ", brass tunnels " + tunnels);
        });
        // the simulation's tracer: the Basin line ends at the Basin, through the built-in funnel
        later(level, 6, () -> {
            List<Tracer.End> ends = Tracer.items(level, BASIN_A.west(), Direction.EAST);
            boolean basin = false;
            for (Tracer.End e : ends) basin |= e.at().equals(BASIN) && Math.abs(e.share() - 1) < 1e-6;
            expect(source, "sim: the tracer follows the Basin line through its built-in funnel into the Basin", basin, ends.toString());
        });
        // and reads a built-in andesite tunnel as it reads Create's stacked one: the same ends, sharing alike
        later(level, 8, () -> {
            String[] seen = new String[2];
            for (int line = 0; line < 2; line++) {
                BlockPos s = new BlockPos(X + 20 + 12 * line, Y, Z);
                // a barrel at each way out for the moment of the trace (the tracer follows a line to an inventory)
                BlockPos[] outs = {s.east(8), s.east(3).south(5)};
                for (BlockPos o : outs) level.setBlock(o, Blocks.BARREL.defaultBlockState(), Block.UPDATE_ALL);
                List<Tracer.End> ends = Tracer.items(level, s.west(), Direction.EAST);
                for (BlockPos o : outs) level.setBlock(o, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                List<String> rel = new ArrayList<>();
                for (Tracer.End e : ends) rel.add(e.at().subtract(s).toShortString() + " x" + String.format(Locale.ROOT, "%.3f", e.share()));
                rel.sort(String::compareTo);
                seen[line] = rel.toString();
            }
            expect(source, "sim: the tracer reads a built-in andesite tunnel as Create's stacked one", seen[0].equals(seen[1]) && !seen[0].equals("[]"),
                "built in " + seen[0] + ", stacked " + seen[1]);
        });
    }

    /**
     * For the smoke script's screenshots: keeps items going onto the test lines for ten seconds (the vertical belts, the
     * tunnel and funnel lines, the Basin line and the Mk belts), so the shots show belts carrying them.
     */
    private static int feedForShots(CommandSourceStack source) {
        ServerLevel level = source.getServer().overworld();
        for (int t = 0; t < 200; t += 10) {
            later(level, t, () -> {
                for (BlockPos start : new BlockPos[]{new BlockPos(X + 70, Y, Z), new BlockPos(X + 74, Y + 5, Z)}) {
                    BeltBlockEntity b = beltAt(level, start);
                    BeltBlockEntity c = b == null ? null : b.getControllerBE();
                    if (c == null || c.getInventory() == null || !c.getInventory().canInsertAt(0)) continue;
                    TransportedItemStack s = new TransportedItemStack(new ItemStack(Items.GOLD_INGOT));
                    s.beltPosition = 0.5f;
                    c.getInventory().addItem(s);
                }
                List<BlockPos> starts = new ArrayList<>(List.of(BASIN_A, new BlockPos(X + 78 - 4, Y, Z)));
                for (int k = 0; k < 2; k++) for (int line = 0; line < 2; line++) {
                    starts.add(new BlockPos(X + 20 + 24 * k + 12 * line, Y, Z));
                    starts.add(new BlockPos(X + 20 + 24 * k + 12 * line, Y, Z + 12));
                }
                for (int i = 0; i < 6; i++) starts.add(new BlockPos(X + 70, Y, Z + 20 + 2 * i));
                for (BlockPos start : starts) feed(level, start, Direction.EAST, new ItemStack(Items.IRON_INGOT));
            });
        }
        report(source, "feeding the lines for the screenshots");
        return 1;
    }

    // ------------------------------------------------------------------------------------------------ after a restart

    /**
     * After the restart: the Basin line's built-in tunnel and funnel are still there, the funnel keeps its filter (iron
     * only), and the line feeds the Basin again: of two iron and two copper, the two iron go in.
     */
    private static int restart(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        REPORT.reset();
        company(level, COMPANY, beltTokens().size() > 1 ? beltTokens().get(1) : "belt:120", true);
        BlockState tunnel = Probe.attached(level, BASIN_TUNNEL), funnel = Probe.attached(level, BASIN_B);
        expect(source, "restart: the Basin line's built-in brass tunnel and brass funnel are still there",
            tunnel != null && tunnel.is(AllBlocks.BRASS_TUNNEL) && funnel != null && funnel.is(AllBlocks.BRASS_BELT_FUNNEL)
                && funnel.getValue(BeltFunnelBlock.HORIZONTAL_FACING) == Direction.WEST, describe(tunnel) + " / " + describe(funnel));
        ServerFilteringBehaviour filter = BlockEntityBehaviour.get(level, BASIN_B, ServerFilteringBehaviour.TYPE);
        expect(source, "restart: the built-in funnel keeps its filter", filter != null && filter.getFilter().is(Items.IRON_INGOT),
            filter == null ? "no filter" : filter.getFilter().toString());
        long before = stored(level, BASIN, Items.IRON_INGOT);
        long copperBefore = stored(level, BASIN, Items.COPPER_INGOT);
        int[] fed = {0};
        for (BeltBlockEntity b : new BeltBlockEntity[]{beltAt(level, BASIN_A)}) if (b != null) SelfMovingBelts.recheck(b);
        // iron first: a copper ingot the filter refuses waits at the funnel, holding back what comes after it (as in Create)
        List<Item> mix = new ArrayList<>(repeat(2, Items.IRON_INGOT));
        mix.addAll(repeat(2, Items.COPPER_INGOT));
        feeder(level, BASIN_A, Direction.EAST, mix, 40, 12, fed);
        later(level, 300, () -> {
            long iron = stored(level, BASIN, Items.IRON_INGOT) - before, copper = stored(level, BASIN, Items.COPPER_INGOT) - copperBefore;
            expect(source, "restart: the line feeds the Basin again as before, the filter letting only iron in", fed[0] == 4 && iron == 2 && copper == 0,
                "fed " + fed[0] + ", iron in " + iron + ", copper in " + copper + ", on the belt " + onBelt(level, BASIN_A, null));
            report(source, "restart done: " + REPORT.totals());
        });
        return 1;
    }
}
