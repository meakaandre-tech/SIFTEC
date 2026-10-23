package com.meakaandre.siftec.command;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.building.BuildingTests;
import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.company.CompanyData;
import com.meakaandre.siftec.depot.DepotBlockEntity;
import com.meakaandre.siftec.drone.DronePortBlockEntity;
import com.meakaandre.siftec.drone.PortContainer;
import com.meakaandre.siftec.governor.GovernorBlockEntity;
import com.meakaandre.siftec.hub.Milestone;
import com.meakaandre.siftec.hub.Milestones;
import com.meakaandre.siftec.owner.Ownership;
import com.meakaandre.siftec.registry.ModBlocks;
import com.meakaandre.siftec.sim.Factories;
import com.meakaandre.siftec.tweak.SelfMovingBelts;
import com.mojang.brigadier.context.CommandContext;
import com.zurrtum.create.AllBlocks;
import com.zurrtum.create.api.behaviour.BlockEntityBehaviour;
import com.zurrtum.create.content.kinetics.base.KineticBlockEntity;
import com.zurrtum.create.content.kinetics.belt.BeltBlockEntity;
import com.zurrtum.create.content.kinetics.belt.behaviour.DirectBeltInputBehaviour;
import com.zurrtum.create.content.kinetics.belt.item.BeltConnectorItem;
import com.zurrtum.create.content.kinetics.belt.transport.TransportedItemStack;
import com.zurrtum.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.zurrtum.create.content.logistics.box.PackageItem;
import com.zurrtum.create.content.logistics.tunnel.BeltTunnelBlock;
import com.zurrtum.create.infrastructure.items.ItemStackHandler;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Selftests for logistics, run by the smoke test: {@code /siftec selftest logistics}. Self-moving
 * belts at every belt tier (and none before Logistics), belts that no shaft or motor drives or is driven by (a running
 * motor on a belt's shaft, a belt an older world saved in a network), a Speed Governor with no company speed
 * limit, the Andesite Tunnel splitting evenly and merging, Drone Ports carrying a package, and the
 * Dimensional Depot uploading from an Item Vault. Every check prints "SELFTEST logistics"; failures say FAIL.
 */
public final class LogisticsTests {
    private static final int X = 4200, Y = 230, Z = 2800;
    private static final int BELT_LENGTH = 20, MEASURE_TICKS = 10;

    private record Later(long at, Runnable run) {
    }

    private static final List<Later> LATER = new ArrayList<>();
    private static final List<Runnable> EVERY_TICK = new ArrayList<>();
    private static int passed, failed;

    private LogisticsTests() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (Runnable r : new ArrayList<>(EVERY_TICK)) r.run();
            long now = server.overworld().getGameTime();
            for (Later l : new ArrayList<>(LATER)) {
                if (l.at > now) continue;
                LATER.remove(l);
                l.run.run();
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            LATER.clear();
            EVERY_TICK.clear();
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
            Commands.literal("siftec").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("selftest").then(Commands.literal("logistics").executes(LogisticsTests::run)))));
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static void later(ServerLevel level, int ticks, Runnable run) {
        LATER.add(new Later(level.getGameTime() + ticks, run));
    }

    private static void report(CommandSourceStack source, String text) {
        String line = "SELFTEST logistics " + text;
        Siftec.LOGGER.info(line);
        source.sendSuccess(() -> Component.literal(line), false);
    }

    private static void expect(CommandSourceStack source, String what, boolean ok, String detail) {
        if (ok) passed++;
        else failed++;
        report(source, (ok ? "PASS " : "FAIL ") + what + (detail.isEmpty() ? "" : " (" + detail + ")"));
    }

    /** A test company that has done every milestone carrying any of these tokens (none: a company that has done nothing). */
    private static Company company(ServerLevel level, CommandSourceStack source, String id, String... tokens) {
        CompanyData data = CompanyData.get(level.getServer());
        Company c = new Company();
        c.id = id;
        c.name = id;
        for (String token : tokens) {
            boolean found = false;
            for (Milestone m : Milestones.all()) {
                if (m.tokens().contains(token)) {
                    c.done.add(m.id());
                    found = true;
                }
            }
            if (!found) expect(source, "a milestone or research gives " + token, false, "none does");
        }
        data.companies().put(id, c);
        return c;
    }

    /** Every milestone's belt token, lowest first: "belt:60" and so on. */
    private static List<String> beltTokens() {
        List<String> out = new ArrayList<>();
        for (Milestone m : Milestones.all()) for (String t : m.tokens()) if (t.startsWith("belt:") && !out.contains(t)) out.add(t);
        out.sort((a, b) -> Integer.compare(Integer.parseInt(a.substring(5)), Integer.parseInt(b.substring(5))));
        return out;
    }

    /**
     * The id of a test company whose belts carry at least this many items a minute: it has done the milestone of the
     * slowest belt tier that does (the fastest, if none is that fast). For other selftests that need belts that move.
     */
    public static String beltCompany(ServerLevel level, int itemsPerMinute) {
        List<String> tokens = beltTokens();
        String token = tokens.isEmpty() ? "" : tokens.getLast();
        for (String t : tokens) {
            if (Integer.parseInt(t.substring(5)) >= itemsPerMinute) {
                token = t;
                break;
            }
        }
        String id = "selftest_belts_" + token.replace(':', '_');
        CompanyData data = CompanyData.get(level.getServer());
        if (data.byId(id) == null) {
            Company c = new Company();
            c.id = id;
            c.name = id;
            for (Milestone m : Milestones.all()) if (m.tokens().contains(token)) c.done.add(m.id());
            data.companies().put(id, c);
        }
        return id;
    }

    /** A Create belt with no shaft driving it, from {@code from} to {@code to} in a straight line, belonging to a company. */
    private static void belt(ServerLevel level, BlockPos from, BlockPos to, String companyId) {
        int dx = Integer.signum(to.getX() - from.getX()), dz = Integer.signum(to.getZ() - from.getZ());
        Direction along = dx != 0 ? (dx > 0 ? Direction.EAST : Direction.WEST) : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
        BlockState shaft = AllBlocks.SHAFT.defaultBlockState().setValue(BlockStateProperties.AXIS, along.getClockWise().getAxis());
        level.setBlock(from, shaft, Block.UPDATE_ALL);
        level.setBlock(to, shaft, Block.UPDATE_ALL);
        BeltConnectorItem.createBelts(level, from, to);
        int length = Math.abs(to.getX() - from.getX()) + Math.abs(to.getZ() - from.getZ());
        for (int i = 0; i <= length; i++) {
            BlockEntity be = level.getBlockEntity(from.offset(dx * i, 0, dz * i));
            if (be != null) be.setAttached(Ownership.OWNER, companyId);
            if (be instanceof BeltBlockEntity b) SelfMovingBelts.recheck(b);
        }
    }

    private static @Nullable BeltBlockEntity beltAt(ServerLevel level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof BeltBlockEntity b ? b : null;
    }

    /** Puts one item on a belt's first block, as a funnel or chute would. */
    private static boolean feed(ServerLevel level, BlockPos beltStart, Direction along, ItemStack one) {
        DirectBeltInputBehaviour in = BlockEntityBehaviour.get(level, beltStart, DirectBeltInputBehaviour.TYPE);
        if (in == null || !in.canInsertFromSide(along)) return false;
        return in.handleInsertion(one.copy(), along, false).isEmpty();
    }

    /** Feeds a belt one item every few ticks, up to a total. */
    private static void feeder(ServerLevel level, BlockPos beltStart, Direction along, ItemStack one, int every, int total, int[] fed) {
        long[] tick = {0};
        EVERY_TICK.add(new Runnable() {
            @Override
            public void run() {
                if (fed[0] >= total) {
                    EVERY_TICK.remove(this);
                    return;
                }
                if (tick[0]++ % every != 0) return;
                if (feed(level, beltStart, along, one)) fed[0]++;
            }
        });
    }

    /** The furthest along item on a belt, or -1. */
    private static float lead(ServerLevel level, BlockPos anySegment) {
        BeltBlockEntity b = beltAt(level, anySegment);
        if (b == null || b.getInventory() == null) return -1;
        float best = -1;
        for (TransportedItemStack t : b.getInventory().getTransportedItems()) best = Math.max(best, t.beltPosition);
        return best;
    }

    /** Items of a kind on a belt, plus those lying on the ground near a spot (what it put off its end). */
    private static int reached(ServerLevel level, BlockPos anySegment, BlockPos end, Item item) {
        int n = 0;
        BeltBlockEntity b = beltAt(level, anySegment);
        if (b != null && b.getInventory() != null) for (TransportedItemStack t : b.getInventory().getTransportedItems()) if (t.stack.is(item)) n += t.stack.getCount();
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, new AABB(end).inflate(2.5))) if (e.getItem().is(item)) n += e.getItem().getCount();
        return n;
    }

    // ------------------------------------------------------------------------------------------------ the test

    private static int run(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        passed = failed = 0;
        BuildingTests.platform(level, X - 10, Z - 10, X + 110, Z + 90, Y, 10);
        // clear what an earlier run left lying about
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, new AABB(X - 10, Y - 1, Z - 10, X + 110, Y + 10, Z + 90))) e.discard();
        later(level, 20, () -> {
            belts(source, level);
            governor(source, level);
            tunnels(source, level);
            drones(source, level);
            depot(source, level);
        });
        later(level, 1500, () -> report(source, "done: " + passed + " passed, " + failed + " failed"));
        report(source, "test site ready; the test runs in a second and reports in about a minute");
        return 1;
    }

    /**
     * One shaftless belt per belt tier, and one for a company before Logistics. Each gets one item; its speed is measured
     * over {@link #MEASURE_TICKS} ticks and must be the tier's items a minute (single items, one per block of belt).
     * Then belts beside a running Creative Motor, owned by a Belt Mk.1 company: no shaft links them, so they run at 24 RPM.
     */
    private static void belts(CommandSourceStack source, ServerLevel level) {
        List<String> tokens = new ArrayList<>(beltTokens());
        expect(source, "six belt tiers among the milestones", tokens.size() == 6, String.valueOf(tokens));
        tokens.addFirst("belt:0");
        for (int row = 0; row < tokens.size(); row++) {
            String token = tokens.get(row);
            int perMinute = Integer.parseInt(token.substring(5));
            Company c = perMinute == 0 ? company(level, source, "selftest_belt_none") : company(level, source, "selftest_belt_" + perMinute, token);
            BlockPos from = new BlockPos(X, Y, Z + 3 * row), to = from.east(BELT_LENGTH - 1);
            belt(level, from, to, c.id);
            later(level, 40, () -> {
                BeltBlockEntity b = beltAt(level, from);
                expect(source, "belt for " + perMinute + " items/min: company speed " + SelfMovingBelts.itemsPerMinute(c),
                    SelfMovingBelts.itemsPerMinute(c) == perMinute, "");
                if (b == null) {
                    expect(source, "belt for " + perMinute + " items/min is there", false, level.getBlockState(from).toString());
                    return;
                }
                float speed = b.getSpeed();
                float rpm = SelfMovingBelts.rpm(perMinute);
                expect(source, "shaftless belt for " + perMinute + " items/min turns at " + rpm + " RPM" + (perMinute > 0 ? " toward its end" : ""),
                    Math.abs(Math.abs(speed) - rpm) < 0.01f && (perMinute == 0 || b.getMovementFacing() == Direction.EAST),
                    speed + " RPM toward " + b.getMovementFacing() + ", theoretical " + b.getTheoreticalSpeed());
                feed(level, from, Direction.EAST, new ItemStack(Items.IRON_INGOT));
            });
            float[] start = {0};
            later(level, 45, () -> start[0] = lead(level, from));
            later(level, 45 + MEASURE_TICKS, () -> {
                float now = lead(level, from);
                float measured = (now - start[0]) / MEASURE_TICKS * 1200;
                // a belt that does not move takes nothing on, as in Create
                boolean ok = perMinute == 0 ? now == start[0] : start[0] >= 0 && Math.abs(measured - perMinute) <= Math.max(1, perMinute * 0.03f);
                expect(source, perMinute == 0 ? "a shaftless belt before Logistics stands still" : "an item on the shaftless belt moves at " + perMinute + " items/min", ok,
                    String.format(Locale.ROOT, "measured %.1f items/min, %.3f -> %.3f blocks", measured, start[0], now));
            });
        }
        // belts never connect to shafts: a Creative Motor at 200 RPM on a belt's end shaft turns neither the belt nor a
        // shaft at its other end; the belt runs at its company's Belt Mk.1 speed and is linked to nothing
        int row = tokens.size();
        Company slow = company(level, source, "selftest_belt_shaft", "belt:60");
        BlockPos from = new BlockPos(X, Y, Z + 3 * row), to = from.east(BELT_LENGTH - 1);
        BlockPos motorPos = from.north(), beyond = to.south();
        // the motor is running before the belt is put down on the shafts, as a player would build it
        level.setBlock(motorPos, AllBlocks.CREATIVE_MOTOR.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.SOUTH), Block.UPDATE_ALL);
        if (level.getBlockEntity(motorPos) instanceof CreativeMotorBlockEntity motor) motor.generatedSpeed.setValue(200);
        level.setBlock(beyond, AllBlocks.SHAFT.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.Z), Block.UPDATE_ALL);
        belt(level, from, to, slow.id);
        later(level, 40, () -> {
            BeltBlockEntity b = beltAt(level, from);
            BeltBlockEntity last = beltAt(level, to);
            float shaft = level.getBlockEntity(beyond) instanceof KineticBlockEntity k ? k.getSpeed() : -1;
            float motor = level.getBlockEntity(motorPos) instanceof KineticBlockEntity k ? k.getSpeed() : 0;
            expect(source, "a belt on a running motor's shaft is not driven by it: it runs at its company's 24 RPM, linked to nothing",
                b != null && last != null && Math.abs(Math.abs(b.getSpeed()) - SelfMovingBelts.rpm(60)) < 0.01f && b.getMovementFacing() == Direction.EAST
                    && !SelfMovingBelts.linked(b) && !SelfMovingBelts.linked(last) && motor != 0,
                b == null ? level.getBlockState(from).toString() : b.getSpeed() + " RPM toward " + b.getMovementFacing() + ", source " + b.hasSource()
                    + ", network " + b.hasNetwork() + "; motor " + motor + " RPM");
            expect(source, "a belt turns no shaft: the shaft at its far end stands still", shaft == 0, shaft + " RPM");
            feed(level, from, Direction.EAST, new ItemStack(Items.IRON_INGOT));
        });
        float[] start = {0};
        later(level, 45, () -> start[0] = lead(level, from));
        later(level, 45 + MEASURE_TICKS, () -> {
            float measured = (lead(level, from) - start[0]) / MEASURE_TICKS * 1200;
            expect(source, "an item on the belt beside the motor moves at the company's 60 items/min", start[0] >= 0 && Math.abs(measured - 60) <= 2,
                String.format(Locale.ROOT, "measured %.1f items/min", measured));
        });
        // a belt an older world saved inside a kinetic network (taking a motor's rotation, and handing it on to a shaft):
        // its first ticks take it out, and the shaft it was turning stops
        BlockPos oldFrom = from.south(2 * 3), oldTo = oldFrom.east(7), oldMotor = oldFrom.north(), oldShaft = oldTo.south();
        level.setBlock(oldMotor, AllBlocks.CREATIVE_MOTOR.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.SOUTH), Block.UPDATE_ALL);
        if (level.getBlockEntity(oldMotor) instanceof CreativeMotorBlockEntity motor) motor.generatedSpeed.setValue(200);
        belt(level, oldFrom, oldTo, slow.id);
        level.setBlock(oldShaft, AllBlocks.SHAFT.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.Z), Block.UPDATE_ALL);
        later(level, 5, () -> {
            for (BlockPos p : new BlockPos[]{oldFrom, oldTo}) {
                if (beltAt(level, p) instanceof BeltBlockEntity b) {
                    b.setSource(oldMotor);
                    b.setSpeed(200);
                }
            }
            if (level.getBlockEntity(oldShaft) instanceof KineticBlockEntity k) {
                k.setSource(oldTo);
                k.setSpeed(200);
            }
        });
        later(level, 45, () -> {
            BeltBlockEntity b = beltAt(level, oldFrom), e = beltAt(level, oldTo);
            float shaft = level.getBlockEntity(oldShaft) instanceof KineticBlockEntity k ? k.getSpeed() : -1;
            boolean shaftSource = level.getBlockEntity(oldShaft) instanceof KineticBlockEntity k && k.hasSource();
            expect(source, "a belt saved in a kinetic network is taken out of it, and the shaft it turned stops",
                b != null && e != null && !SelfMovingBelts.linked(b) && !SelfMovingBelts.linked(e) && shaft == 0 && !shaftSource
                    && Math.abs(Math.abs(b.getSpeed()) - SelfMovingBelts.rpm(60)) < 0.01f,
                b == null ? "no belt" : "belt source " + b.hasSource() + ", network " + b.hasNetwork() + ", theoretical " + b.getTheoreticalSpeed()
                    + ", speed " + b.getSpeed() + "; shaft " + shaft + " RPM, source " + shaftSource);
        });
    }

    /** A Speed Governor set to 200 RPM gives 200 RPM: there is no company cap. */
    private static void governor(CommandSourceStack source, ServerLevel level) {
        Company none = company(level, source, "selftest_governor");
        BlockPos at = new BlockPos(X + 30, Y + 1, Z);
        level.setBlock(at.above(), AllBlocks.CREATIVE_MOTOR.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.DOWN), Block.UPDATE_ALL);
        level.setBlock(at, ModBlocks.SPEED_GOVERNOR.get().defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.Y), Block.UPDATE_ALL);
        level.setBlock(at.below(), AllBlocks.SHAFT.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.Y), Block.UPDATE_ALL);
        for (BlockPos p : new BlockPos[]{at.above(), at, at.below()}) {
            BlockEntity be = level.getBlockEntity(p);
            if (be != null) be.setAttached(Ownership.OWNER, none.id);
        }
        later(level, 10, () -> {
            if (level.getBlockEntity(at) instanceof GovernorBlockEntity g) g.setTarget(200);
        });
        later(level, 60, () -> {
            int target = level.getBlockEntity(at) instanceof GovernorBlockEntity g ? g.target() : -1;
            float out = level.getBlockEntity(at.below()) instanceof KineticBlockEntity k ? Math.abs(k.getSpeed()) : -1;
            expect(source, "a Speed Governor set to 200 RPM gives 200 RPM for a company with no milestones", target == 200 && out == 200,
                "target " + target + ", shaft below " + out + " RPM");
        });
    }

    /**
     * Shaftless belts at Belt Mk.2. Split: thirty single items through an Andesite Tunnel with a belt leaving on each side
     * come out ten, ten and ten. Merge: a belt running into the side of another under an Andesite Tunnel; everything
     * from both reaches the far end.
     */
    private static void tunnels(CommandSourceStack source, ServerLevel level) {
        Company c = company(level, source, "selftest_tunnel", "belt:120");
        // split
        BlockPos main = new BlockPos(X + 40, Y, Z + 10), mainEnd = main.east(11), mid = main.east(5);
        belt(level, main, mainEnd, c.id);
        BlockPos northStart = mid.north(), northEnd = mid.north(5), southStart = mid.south(), southEnd = mid.south(5);
        belt(level, northStart, northEnd, c.id);
        belt(level, southStart, southEnd, c.id);
        level.setBlock(mid.above(), AllBlocks.ANDESITE_TUNNEL.defaultBlockState(), Block.UPDATE_ALL);
        AllBlocks.ANDESITE_TUNNEL.updateTunnel(level, mid.above());
        int[] fed = {0};
        later(level, 40, () -> {
            BlockState tunnel = level.getBlockState(mid.above());
            expect(source, "the splitting tunnel is a junction", tunnel.is(AllBlocks.ANDESITE_TUNNEL) && BeltTunnelBlock.isJunction(tunnel), tunnel.toString());
            feeder(level, main, Direction.EAST, new ItemStack(Items.COPPER_INGOT), 12, 30, fed);
        });
        later(level, 1100, () -> {
            int straight = reached(level, main, mainEnd.east(), Items.COPPER_INGOT) - onBeltBefore(level, main, mid, Items.COPPER_INGOT);
            int north = reached(level, northStart, northEnd.north(), Items.COPPER_INGOT);
            int south = reached(level, southStart, southEnd.south(), Items.COPPER_INGOT);
            expect(source, "the Andesite Tunnel splits 30 items evenly: 10 straight on, 10 to each side", fed[0] == 30 && straight == 10 && north == 10 && south == 10,
                "fed " + fed[0] + "; straight " + straight + ", north " + north + ", south " + south);
        });
        // merge
        BlockPos main2 = new BlockPos(X + 40, Y, Z + 30), main2End = main2.east(11), mid2 = main2.east(5);
        belt(level, main2, main2End, c.id);
        BlockPos sideStart = mid2.south(5), sideEnd = mid2.south();
        belt(level, sideStart, sideEnd, c.id);
        level.setBlock(mid2.above(), AllBlocks.ANDESITE_TUNNEL.defaultBlockState(), Block.UPDATE_ALL);
        AllBlocks.ANDESITE_TUNNEL.updateTunnel(level, mid2.above());
        int[] fedMain = {0}, fedSide = {0};
        later(level, 40, () -> {
            feeder(level, main2, Direction.EAST, new ItemStack(Items.GOLD_INGOT), 15, 10, fedMain);
            feeder(level, sideStart, Direction.NORTH, new ItemStack(Items.GOLD_INGOT), 15, 10, fedSide);
        });
        later(level, 1100, () -> {
            int end = reached(level, main2, main2End.east(), Items.GOLD_INGOT);
            int side = reached(level, sideStart, sideStart, Items.GOLD_INGOT);
            expect(source, "the Andesite Tunnel merges a side belt: all 20 items reach the main belt's end", fedMain[0] == 10 && fedSide[0] == 10 && end == 20,
                "fed " + fedMain[0] + " + " + fedSide[0] + "; at the end " + end + ", left on the side belt " + side);
        });
    }

    /** Items still on a belt before a segment (not yet through the tunnel), so the straight-on count leaves them out. */
    private static int onBeltBefore(ServerLevel level, BlockPos anySegment, BlockPos segment, Item item) {
        BeltBlockEntity b = beltAt(level, anySegment);
        if (b == null || b.getInventory() == null) return 0;
        int index = segment.getX() - anySegment.getX();
        int n = 0;
        for (TransportedItemStack t : b.getInventory().getTransportedItems()) if (t.stack.is(item) && t.beltPosition < index) n += t.stack.getCount();
        return n;
    }

    /**
     * Two Drone Ports 20 blocks apart: a package and three fire charges at the first; the drone takes the package over,
     * burns one charge and comes home. A port takes only packages and fire charges; a trip of 80 blocks needs two charges.
     */
    private static void drones(CommandSourceStack source, ServerLevel level) {
        Company c = company(level, source, "selftest_drones");
        // Drone Port buildings (23 by 23), on a site of their own: terminals 40 apart, a trip there burns one charge, 80 two
        int dx = X + 400, dz = Z;
        BuildingTests.platform(level, dx - 20, dz - 20, dx + 100, dz + 80, Y, 16);
        BlockPos home = new BlockPos(dx, Y + 1, dz + 10), far = home.east(40), farther = home.east(80);
        DronePortBlockEntity port = PolishTests.buildDronePort(level, c, home);
        PolishTests.buildDronePort(level, c, far);
        if (port == null || !(level.getBlockEntity(far) instanceof DronePortBlockEntity)) {
            expect(source, "the Drone Port buildings go down", false, level.getBlockState(home).toString());
            return;
        }
        ItemStack parcel = PackageItem.containing(List.of(new ItemStack(Items.IRON_INGOT, 5)));
        expect(source, "a Drone Port takes packages and fire charges, not loose items",
            port.items.canPlaceItem(PortContainer.OUT, parcel) && !port.items.canPlaceItem(PortContainer.OUT, new ItemStack(Items.IRON_INGOT))
                && port.items.canPlaceItem(PortContainer.FUEL, new ItemStack(Items.FIRE_CHARGE)), "");
        port.companyId = c.id;
        port.hasDrone = true;
        port.destination = farther;
        int longTrip = port.chargesNeeded();
        port.destination = far;
        expect(source, "a drone trip burns one fire charge for 40 blocks, two for 80", port.chargesNeeded() == 1 && longTrip == 2,
            port.chargesNeeded() + " and " + longTrip);
        port.items.setItem(PortContainer.FUEL, new ItemStack(Items.FIRE_CHARGE, 3));
        port.items.setItem(PortContainer.OUT, parcel);
        port.setChanged();
        later(level, 400, () -> {
            DronePortBlockEntity a = level.getBlockEntity(home) instanceof DronePortBlockEntity p ? p : null;
            DronePortBlockEntity b = level.getBlockEntity(far) instanceof DronePortBlockEntity p ? p : null;
            ItemStack arrived = b == null ? ItemStack.EMPTY : b.items.getItem(PortContainer.IN);
            int inside = PackageItem.isPackage(arrived) ? countIn(PackageItem.getContents(arrived), Items.IRON_INGOT) : 0;
            expect(source, "the drone delivered the package (5 iron ingots) and came home on one fire charge",
                a != null && a.state == DronePortBlockEntity.IDLE && a.fuel().getCount() == 2 && a.items.getItem(PortContainer.OUT).isEmpty() && inside == 5,
                (a == null ? "home port missing" : "home state " + a.state + ", fire charges " + a.fuel().getCount()) + "; arrived " + arrived + " holding " + inside);
        });
        // a far port whose chunk does not tick: the drone unloads there all the same, into the
        // simulation's record of that port, and comes home; loaded again, the port holds the package
        BlockPos lonely = home.south(40), nowhere = home.offset(5_000, 0, 0);
        BuildingTests.platform(level, nowhere.getX() - 16, nowhere.getZ() - 16, nowhere.getX() + 16, nowhere.getZ() + 16, Y, 16);
        DronePortBlockEntity faraway = PolishTests.buildDronePort(level, c, nowhere);
        DronePortBlockEntity stray = PolishTests.buildDronePort(level, c, lonely);
        if (faraway == null || stray == null) {
            expect(source, "the far and lonely Drone Port buildings go down", false, "");
            return;
        }
        stray.companyId = c.id;
        stray.hasDrone = true;
        stray.destination = nowhere;
        stray.setChanged();
        // the far port ticks once (the simulation learns of it), then its chunk is let go; then the drone sets off
        later(level, 60, () -> forceAround(level, nowhere, false));
        later(level, 140, () -> {
            if (!(level.getBlockEntity(lonely) instanceof DronePortBlockEntity p)) return;
            p.items.setItem(PortContainer.FUEL, new ItemStack(Items.FIRE_CHARGE, 64));
            p.items.setItem(PortContainer.OUT, PackageItem.containing(List.of(new ItemStack(Items.GOLD_INGOT, 3))));
            p.setChanged();
        });
        later(level, 200, () -> {
            if (level.getBlockEntity(lonely) instanceof DronePortBlockEntity p) {
                var farNode = Factories.node(level, nowhere);
                expect(source, "a drone sets off for a port whose chunk does not tick", p.state == DronePortBlockEntity.OUTBOUND && !level.isLoaded(nowhere)
                    && farNode != null && farNode.sim, "state " + p.state + ", far port " + (farNode == null ? "unknown" : farNode.sim ? "simulated" : "not simulated"));
                p.land();
            }
        });
        later(level, 260, () -> {
            DronePortBlockEntity p = level.getBlockEntity(lonely) instanceof DronePortBlockEntity q ? q : null;
            var farNode = Factories.node(level, nowhere);
            long arrived = 0;
            if (farNode != null) for (int i = 10; i < farNode.slots.size(); i++) if (!farNode.slots.get(i).empty()) arrived += farNode.slots.get(i).count;
            expect(source, "there it unloads into the simulated port and heads home: no waiting, nothing brought back",
                p != null && p.state == DronePortBlockEntity.HOMEBOUND && arrived == 1 && !level.isLoaded(nowhere),
                p == null ? "port missing" : "state " + p.state + ", packages at the far port " + arrived);
            if (p != null) p.land();
        });
        later(level, 300, () -> {
            DronePortBlockEntity p = level.getBlockEntity(lonely) instanceof DronePortBlockEntity q ? q : null;
            expect(source, "home again, nothing waits to leave", p != null && p.state == DronePortBlockEntity.IDLE && p.items.empty(PortContainer.OUT, PortContainer.FUEL),
                p == null ? "port missing" : "state " + p.state + ", status " + p.statusKey());
            // the far port's chunk loads again: the catch-up puts the package in its arrivals
            forceAround(level, nowhere, true);
        });
        later(level, 360, () -> {
            DronePortBlockEntity farPort = level.getBlockEntity(nowhere) instanceof DronePortBlockEntity q ? q : null;
            ItemStack arrived = farPort == null ? ItemStack.EMPTY : farPort.items.getItem(PortContainer.IN);
            int gold = PackageItem.isPackage(arrived) ? countIn(PackageItem.getContents(arrived), Items.GOLD_INGOT) : 0;
            expect(source, "loaded again, the far port holds the package (3 gold ingots) among its arrivals", gold == 3,
                farPort == null ? "port missing" : "arrivals " + arrived);
            forceAround(level, nowhere, false);
        });
    }

    /** Forces (or lets go) the chunks a Drone Port building's platform round a terminal loaded. */
    private static void forceAround(ServerLevel level, BlockPos at, boolean force) {
        for (int cx = (at.getX() - 16) >> 4; cx <= (at.getX() + 16) >> 4; cx++) {
            for (int cz = (at.getZ() - 16) >> 4; cz <= (at.getZ() + 16) >> 4; cz++) {
                level.setChunkForced(cx, cz, force);
                if (force) level.getChunk(cx, cz);
            }
        }
    }

    private static int countIn(ItemStackHandler handler, Item item) {
        int n = 0;
        for (int i = 0; i < handler.getContainerSize(); i++) if (handler.getItem(i).is(item)) n += handler.getItem(i).getCount();
        return n;
    }

    /**
     * Dimensional Depots on Item Vaults, at Satisfactory 1.0's rates. A company with no research uploads 15 items a minute
     * (2 in eight seconds), one with Upload Upgrade 240/min 4 a second (32); a depot on a chest uploads nothing; the cloud
     * holds one stack of an item, two with Depot Expansion (200%); taking from the cloud gives a stack; the Manual Depot
     * Uploader research lets a member upload from their inventory.
     */
    private static void depot(CommandSourceStack source, ServerLevel level) {
        Company plain = company(level, source, "selftest_depot");
        Company fast = company(level, source, "selftest_depot_upload", "depot:upload:240");
        Company stacked = company(level, source, "selftest_depot_stacks", "depot:upload:240", "depot:stacks:2");
        Company manual = company(level, source, "selftest_depot_manual", "depot:manual");
        stacked.cloud.put("minecraft:gold_ingot", 2 * 64 - 4);
        expect(source, "depot rates before and after research: 15 and 240 items/min, 1 and 2 stacks",
            DepotBlockEntity.uploadPerMinute(plain) == 15 && DepotBlockEntity.uploadPerMinute(fast) == 240 && DepotBlockEntity.cloudStacks(plain) == 1
                && DepotBlockEntity.cloudStacks(stacked) == 2 && DepotBlockEntity.cloudLimit(plain, Items.ENDER_PEARL) == 16,
            DepotBlockEntity.uploadPerMinute(plain) + "/" + DepotBlockEntity.uploadPerMinute(fast) + " a minute, " + DepotBlockEntity.cloudStacks(plain) + "/"
                + DepotBlockEntity.cloudStacks(stacked) + " stacks, pearls " + DepotBlockEntity.cloudLimit(plain, Items.ENDER_PEARL));
        BlockPos vaultA = new BlockPos(X + 70, Y, Z + 40), vaultB = vaultA.east(4), chest = vaultA.east(8), vaultC = vaultA.east(12);
        for (BlockPos v : new BlockPos[]{vaultA, vaultB, vaultC}) level.setBlock(v, AllBlocks.ITEM_VAULT.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(chest, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        later(level, 5, () -> {
            insert(level, vaultA, new ItemStack(Items.IRON_INGOT, 64), new ItemStack(Items.IRON_INGOT, 36));
            insert(level, vaultB, new ItemStack(Items.REDSTONE, 64), new ItemStack(Items.REDSTONE, 36));
            insert(level, chest, new ItemStack(Items.COPPER_INGOT, 64));
            insert(level, vaultC, new ItemStack(Items.GOLD_INGOT, 20));
            Object[][] depots = {{vaultA, plain}, {vaultB, fast}, {chest, plain}, {vaultC, stacked}};
            for (Object[] d : depots) {
                BlockPos at = ((BlockPos) d[0]).above();
                level.setBlock(at, ModBlocks.DIMENSIONAL_DEPOT.get().defaultBlockState(), Block.UPDATE_ALL);
                if (level.getBlockEntity(at) instanceof DepotBlockEntity depot) {
                    depot.companyId = ((Company) d[1]).id;
                    depot.setChanged();
                }
            }
        });
        int[] first = new int[3];
        later(level, 35, () -> {
            first[0] = plain.cloud.getOrDefault("minecraft:iron_ingot", 0);
            first[1] = fast.cloud.getOrDefault("minecraft:redstone", 0);
        });
        later(level, 195, () -> {
            int plainRate = plain.cloud.getOrDefault("minecraft:iron_ingot", 0) - first[0];
            int fastRate = fast.cloud.getOrDefault("minecraft:redstone", 0) - first[1];
            expect(source, "a Dimensional Depot on an Item Vault uploads 15 items a minute", plainRate == 2, plainRate + " in eight seconds");
            expect(source, "with Upload Upgrade 240/min it uploads 4 a second", fastRate == 32, fastRate + " in eight seconds");
            expect(source, "a depot on a chest uploads nothing", !plain.cloud.containsKey("minecraft:copper_ingot") && count(level, chest, Items.COPPER_INGOT) == 64,
                plain.cloud.getOrDefault("minecraft:copper_ingot", 0) + " copper in the cloud");
            int gold = stacked.cloud.getOrDefault("minecraft:gold_ingot", 0);
            expect(source, "with Depot Expansion (200%) the cloud holds two stacks of an item", gold == 128 && count(level, vaultC, Items.GOLD_INGOT) == 16,
                gold + " in the cloud, " + count(level, vaultC, Items.GOLD_INGOT) + " left in the vault");
            int before = fast.cloud.getOrDefault("minecraft:redstone", 0);
            ItemStack taken = DepotBlockEntity.withdraw(fast, "minecraft:redstone");
            int after = fast.cloud.getOrDefault("minecraft:redstone", 0);
            expect(source, "taking from the cloud gives what it held, up to a stack", taken.is(Items.REDSTONE) && taken.getCount() == Math.min(64, before) && after == before - taken.getCount(),
                "had " + before + ", took " + taken + ", left " + after);
            ItemStack hand = new ItemStack(Items.DIAMOND, 40);
            int up = DepotBlockEntity.upload(manual, hand);
            ItemStack more = new ItemStack(Items.DIAMOND, 40);
            int upMore = DepotBlockEntity.upload(manual, more);
            expect(source, "Manual Depot Uploader: a member uploads from their inventory, up to the cloud's limit",
                DepotBlockEntity.manual(manual) && !DepotBlockEntity.manual(plain) && up == 40 && hand.isEmpty() && upMore == 24 && more.getCount() == 16
                    && manual.cloud.getOrDefault("minecraft:diamond", 0) == 64,
                "uploaded " + up + " then " + upMore + ", " + more.getCount() + " kept, " + manual.cloud.getOrDefault("minecraft:diamond", 0) + " in the cloud");
            Companies.save(level.getServer());
        });
    }

    private static void insert(ServerLevel level, BlockPos pos, ItemStack... stacks) {
        Storage<ItemVariant> s = ItemStorage.SIDED.find(level, pos, null);
        if (s == null) return;
        try (Transaction tx = Transaction.openOuter()) {
            for (ItemStack stack : stacks) s.insert(ItemVariant.of(stack), stack.getCount(), tx);
            tx.commit();
        }
    }

    private static int count(ServerLevel level, BlockPos pos, Item item) {
        Storage<ItemVariant> s = ItemStorage.SIDED.find(level, pos, null);
        int n = 0;
        if (s != null) for (StorageView<ItemVariant> v : s) if (!v.isResourceBlank() && v.getResource().isOf(item)) n += (int) v.getAmount();
        return n;
    }
}
