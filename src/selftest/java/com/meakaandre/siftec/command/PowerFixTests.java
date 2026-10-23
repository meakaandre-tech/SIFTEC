package com.meakaandre.siftec.command;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.TestQueue;
import com.meakaandre.siftec.TestReport;
import com.meakaandre.siftec.building.BuildGun;
import com.meakaandre.siftec.building.BuildGunPayloads;
import com.meakaandre.siftec.building.BuildingDef;
import com.meakaandre.siftec.building.BuildingNodes;
import com.meakaandre.siftec.building.BuildingTests;
import com.meakaandre.siftec.building.Buildings;
import com.meakaandre.siftec.building.ControllerBlockEntity;
import com.meakaandre.siftec.building.Placement;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.company.CompanyData;
import com.meakaandre.siftec.node.NodeLook;
import com.meakaandre.siftec.node.NodeSites;
import com.meakaandre.siftec.node.NodeType;
import com.meakaandre.siftec.node.Purity;
import com.meakaandre.siftec.owner.Ownership;
import com.meakaandre.siftec.power.PowerMemory;
import com.meakaandre.siftec.power.PowerTests;
import com.meakaandre.siftec.tweak.SelfMovingBelts;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.context.CommandContext;
import com.zurrtum.create.AllBlocks;
import com.zurrtum.create.content.kinetics.base.RotatedPillarKineticBlock;
import com.zurrtum.create.content.kinetics.belt.BeltBlockEntity;
import com.zurrtum.create.content.kinetics.belt.item.BeltConnectorItem;
import com.zurrtum.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.zurrtum.create.content.kinetics.transmission.GearshiftBlock;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * {@code /siftec selftest powerfix}: power, belt and miner checks, on a test site of their own. Every check prints
 * "SELFTEST powerfix"; failures say FAIL; the last line counts them.
 * <ol>
 * <li>14, power over unloaded chunks: a fuelled Biomass Burner 640 blocks east is wired to a Power Pole by a Constructor
 * and an empty Biomass Burner. Its chunk is let go and unloads; the grid goes on as before on what it remembers of the
 * far burner (no fuse blown, the Constructor powered), and when it loads again it burns the fuel it gave meanwhile;</li>
 * <li>15, self-moving belts: a belt behind a clutch powered by redstone (cut off from its Creative Motor) stands still;
 * one behind an unpowered clutch runs at the motor's speed; one with no drive at all moves by itself;</li>
 * <li>16, miners on nodes: the spot a client sends near an ore node is snapped onto it on the server, and the verdict
 * sent back (through its stream codec) carries the snapped spot and the one sent.</li>
 * </ol>
 */
public final class PowerFixTests {
    private static final int X = 13200, Y = 200, Z = -13200;
    /** The far burner: far enough east that nothing near the site keeps its chunk loaded. */
    private static final int FAR = 640;
    private static final String COMPANY = "selftest_powerfix";
    private static final UUID GUNNER = UUID.fromString("5e1f7e57-f1c5-4000-8000-000000000014");

    private static final TestQueue LATER = new TestQueue();
    private static final TestReport REPORT = new TestReport("SELFTEST powerfix ");
    private static int pending;

    private PowerFixTests() {
    }

    public static void register() {
        LATER.register();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
            Commands.literal("siftec").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("selftest").then(Commands.literal("powerfix").executes(PowerFixTests::run)))));
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static void later(ServerLevel level, int ticks, Runnable run) {
        pending++;
        LATER.add(level.getServer().overworld().getGameTime() + ticks, () -> {
            try {
                run.run();
            } finally {
                pending--;
            }
        });
    }

    /** Runs {@code then} once the condition holds, or after {@code max} ticks either way. */
    private static void when(ServerLevel level, BooleanSupplier condition, int max, Runnable then) {
        later(level, 5, () -> {
            if (condition.getAsBoolean() || max <= 5) then.run();
            else when(level, condition, max - 5, then);
        });
    }

    private static void report(CommandSourceStack source, String text) {
        REPORT.report(source, text);
    }

    private static void expect(CommandSourceStack source, String what, boolean ok, String detail) {
        REPORT.expect(source, what, ok, detail);
    }

    private static void run(CommandSourceStack source, String part, Runnable checks) {
        try {
            checks.run();
        } catch (RuntimeException e) {
            Siftec.LOGGER.error("SIFTEC: selftest powerfix {} threw", part, e);
            expect(source, part + " runs to the end", false, e.toString());
        }
    }

    private static void finish(CommandSourceStack source, ServerLevel level) {
        if (pending > 0) {
            LATER.add(level.getServer().overworld().getGameTime() + 10, () -> finish(source, level));
            return;
        }
        report(source, "done: " + REPORT.totals());
    }

    /** A test company that has done the milestones given. */
    private static Company company(ServerLevel level, String id, String... done) {
        CompanyData data = CompanyData.get(level.getServer());
        Company c = new Company();
        c.id = id;
        c.name = id;
        c.done.addAll(List.of(done));
        data.companies().put(id, c);
        data.setDirty();
        return c;
    }

    private static String at(@Nullable BlockPos pos) {
        return pos == null ? "none" : pos.toShortString();
    }

    // ------------------------------------------------------------------------------------------------ the test

    private static int run(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        REPORT.reset();
        pending = 0;
        BuildingTests.platform(level, X - 8, Z - 8, X + 72, Z + 40, Y, 16);
        BuildingTests.platform(level, X + FAR - 8, Z - 8, X + FAR + 8, Z + 8, Y, 16);
        later(level, 10, () -> {
            run(source, "14 power over unloaded chunks", () -> power(source, level));
            run(source, "15 self-moving belts", () -> belts(source, level));
            run(source, "16 miners on nodes", () -> snapping(source, level));
            finish(source, level);
        });
        report(source, "test site ready; the checks report over the next minute or two");
        return 1;
    }

    // ------------------------------------------------------------------------------------------------ 14: power

    private static void power(CommandSourceStack source, ServerLevel level) {
        BlockPos poleAt = new BlockPos(X + 4, Y, Z + 4), emptyAt = new BlockPos(X + 4, Y, Z + 16), conAt = new BlockPos(X + 16, Y, Z + 16),
            farAt = new BlockPos(X + FAR, Y, Z);
        ControllerBlockEntity pole = PowerTests.build(level, source, "power_pole_mk1", poleAt, Direction.SOUTH);
        ControllerBlockEntity empty = PowerTests.build(level, source, "biomass_burner", emptyAt, Direction.SOUTH);
        ControllerBlockEntity con = PowerTests.build(level, source, "constructor", conAt, Direction.SOUTH);
        ControllerBlockEntity far = PowerTests.build(level, source, "biomass_burner", farAt, Direction.SOUTH);
        if (pole == null || empty == null || con == null || far == null) {
            expect(source, "14: the test grid goes down", false, "pole " + (pole != null) + ", empty burner " + (empty != null) + ", constructor " + (con != null) + ", far burner " + (far != null));
            return;
        }
        far.input.setItem(0, new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse("siftec:solid_biofuel")), 64));
        BlockPos farController = far.getBlockPos(), conController = con.getBlockPos(), emptyController = empty.getBlockPos();
        int farCx = farController.getX() >> 4, farCz = farController.getZ() >> 4;
        later(level, 5, () -> {
            boolean w = PowerTests.wire(PowerTests.connector(level, far), PowerTests.connector(level, pole))
                & PowerTests.wire(PowerTests.connector(level, empty), PowerTests.connector(level, pole))
                & PowerTests.wire(PowerTests.connector(level, con), PowerTests.connector(level, pole));
            report(source, "14: wired " + w);
        });
        later(level, 60, () -> {
            boolean ok = con.powered && !empty.tripped && !far.tripped && far.lastGenerated > 0;
            expect(source, "14: with everything loaded the far burner runs the Constructor", ok,
                "constructor powered " + con.powered + ", far burner gives " + far.lastGenerated + ", tripped " + empty.tripped + "/" + far.tripped);
            // let the far burner's chunks go (the platform forced them) and wait for them to unload
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) level.setChunkForced(farCx + dx, farCz + dz, false);
            when(level, () -> !level.hasChunk(farCx, farCz), 2400, () -> unloaded(source, level, farController, conController, emptyController, farCx, farCz));
        });
    }

    private static void unloaded(CommandSourceStack source, ServerLevel level, BlockPos farController, BlockPos conController, BlockPos emptyController, int farCx, int farCz) {
        boolean gone = !level.hasChunk(farCx, farCz);
        expect(source, "14: the far burner's chunk unloads", gone, "");
        if (!gone) return;
        later(level, 60, () -> {
            ControllerBlockEntity con = level.getBlockEntity(conController) instanceof ControllerBlockEntity c ? c : null;
            ControllerBlockEntity empty = level.getBlockEntity(emptyController) instanceof ControllerBlockEntity c ? c : null;
            PowerMemory.Remembered far = PowerMemory.get(level).at(level, farController);
            expect(source, "14: the far burner unloaded, the grid's fuse holds and the Constructor still has power",
                con != null && con.powered && empty != null && !empty.tripped,
                "constructor powered " + (con != null && con.powered) + ", fuse blown " + (empty != null && empty.tripped) + "; " + (con == null || con.grid == null ? "no grid" : con.grid.describe()));
            double spent = far == null ? 0 : far.spent();
            expect(source, "14: the far burner is remembered while away and gives power on what it held",
                far != null && far.away() && spent > 0 && far.capacity() > 0, far == null ? "not remembered" : "away " + far.away() + ", spent " + spent + " SU-ticks, gives " + far.capacity()
                    + "; " + PowerMemory.get(level).describe(level));
            // back: what it gave while away is burnt from its fuel
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) level.setChunkForced(farCx + dx, farCz + dz, true);
            level.getChunk(farCx, farCz);
            later(level, 60, () -> {
                ControllerBlockEntity back = level.getBlockEntity(farController) instanceof ControllerBlockEntity c ? c : null;
                PowerMemory.Remembered r = PowerMemory.get(level).at(level, farController);
                ItemStack fuel = back == null ? ItemStack.EMPTY : back.input.getItem(0);
                expect(source, "14: loaded again, the far burner counts for itself and took over what it gave while away",
                    back != null && r != null && !r.away() && r.spent() == 0 && !back.tripped,
                    back == null ? "not loaded" : "fuel left " + fuel.getCount() + ", banked " + Math.round(back.energy) + ", remembered away " + (r != null && r.away())
                        + ", given while away " + spent);
                for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) level.setChunkForced(farCx + dx, farCz + dz, false);
            });
        });
    }

    // ------------------------------------------------------------------------------------------------ 15: belts

    /** A belt from {@code from} east, of a company, with nothing driving it yet. */
    private static void belt(ServerLevel level, BlockPos from, BlockPos to, String companyId) {
        BlockState shaft = AllBlocks.SHAFT.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.Z);
        level.setBlock(from, shaft, Block.UPDATE_ALL);
        level.setBlock(to, shaft, Block.UPDATE_ALL);
        BeltConnectorItem.createBelts(level, from, to);
        for (int i = 0; i <= to.getX() - from.getX(); i++) {
            BlockEntity be = level.getBlockEntity(from.east(i));
            if (be != null) be.setAttached(Ownership.OWNER, companyId);
            if (be instanceof BeltBlockEntity b) SelfMovingBelts.recheck(b);
        }
    }

    /** A Creative Motor at 200 RPM two blocks north of a belt's start, with a clutch between (powered or not). */
    private static void clutched(ServerLevel level, BlockPos from, boolean cutOff) {
        BlockPos clutch = from.north(), motor = from.north(2);
        BlockState c = AllBlocks.CLUTCH.defaultBlockState().setValue(RotatedPillarKineticBlock.AXIS, Direction.Axis.Z).setValue(GearshiftBlock.POWERED, cutOff);
        if (cutOff) level.setBlock(clutch.west(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(clutch, c, Block.UPDATE_ALL);
        level.setBlock(motor, AllBlocks.CREATIVE_MOTOR.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.SOUTH), Block.UPDATE_ALL);
        if (level.getBlockEntity(motor) instanceof CreativeMotorBlockEntity m) m.generatedSpeed.setValue(200);
    }

    /**
     * Belts never connect to shafts: a motor through a clutch, engaged or not, turns no belt, and every belt runs at its company's self-moving speed.
     */
    private static void belts(CommandSourceStack source, ServerLevel level) {
        Company c = company(level, COMPANY, "hub_upgrade_4");
        float self = SelfMovingBelts.rpm(SelfMovingBelts.itemsPerMinute(c));
        BlockPos cut = new BlockPos(X + 30, Y, Z + 4), driven = new BlockPos(X + 30, Y, Z + 12), free = new BlockPos(X + 30, Y, Z + 20);
        belt(level, cut, cut.east(7), c.id);
        belt(level, driven, driven.east(7), c.id);
        belt(level, free, free.east(7), c.id);
        clutched(level, cut, true);
        clutched(level, driven, false);
        later(level, 40, () -> {
            BeltBlockEntity a = level.getBlockEntity(cut) instanceof BeltBlockEntity b ? b : null;
            BeltBlockEntity d = level.getBlockEntity(driven) instanceof BeltBlockEntity b ? b : null;
            BeltBlockEntity f = level.getBlockEntity(free) instanceof BeltBlockEntity b ? b : null;
            for (var e : new Object[][]{{"behind an engaged clutch", a}, {"behind a disengaged clutch and a running motor", d}, {"with nothing beside it", f}}) {
                BeltBlockEntity b = (BeltBlockEntity) e[1];
                expect(source, "15: a belt " + e[0] + " runs at its company's " + self + " RPM, linked to nothing",
                    b != null && self > 0 && Math.abs(Math.abs(b.getSpeed()) - self) < 0.01f && !SelfMovingBelts.linked(b),
                    b == null ? "no belt" : b.getSpeed() + " RPM, source " + b.hasSource() + ", network " + b.hasNetwork() + ", theoretical " + b.getTheoreticalSpeed());
            }
        });
    }

    // ------------------------------------------------------------------------------------------------ 16: miners

    private static void snapping(CommandSourceStack source, ServerLevel level) {
        BuildingDef miner = Buildings.def("miner_mk1");
        if (miner == null) {
            expect(source, "16: the miner_mk1 building exists", false, "");
            return;
        }
        FakePlayer gunner = FakePlayer.get(level, new GameProfile(GUNNER, "powerfix_gunner"));
        gunner.setPos(X + 50.5, Y, Z + 30.5);
        BlockPos node = new BlockPos(X + 50, Y - 1, Z + 10);
        NodeSites.put(level, node, NodeLook.of(NodeType.IRON), Purity.PURE);
        try {
            // the client's hologram, aimed two blocks off the node (it does not know where the nodes are)
            Placement aimed = new Placement(miner, BlockPos.ZERO, Direction.SOUTH, false);
            BlockPos cell = BuildingNodes.ownNodeCell(aimed);
            if (cell == null) {
                expect(source, "16: the miner has a node cell", false, "");
                return;
            }
            BlockPos sent = node.offset(2 - cell.getX(), -cell.getY(), 1 - cell.getZ());
            BuildGun.selected(gunner, new BuildGunPayloads.Select(miner.id(), false, true, sent, Direction.SOUTH.get2DDataValue(), false));
            Placement spot = BuildGun.selection(gunner).spot;
            BlockPos landed = spot == null ? null : BuildingNodes.ownNodeCell(spot);
            expect(source, "16: the server snaps a miner's spot onto the node near where it was aimed", node.equals(landed),
                "sent " + at(sent) + ", node cell now " + at(landed) + ", node " + at(node));
            BuildGunPayloads.View view = BuildGun.view(gunner);
            BuildGunPayloads.View decoded = null;
            if (view != null) {
                RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
                BuildGunPayloads.View.STREAM_CODEC.encode(buf, view);
                decoded = BuildGunPayloads.View.STREAM_CODEC.decode(buf);
            }
            expect(source, "16: the verdict the client gets carries the snapped spot and the one it sent", decoded != null && spot != null
                    && decoded.snapped() && decoded.min().equals(spot.anchor()) && decoded.from().equals(sent),
                decoded == null ? "no verdict" : "spot " + at(decoded.min()) + " from " + at(decoded.from()) + ", snapped " + decoded.snapped());
        } finally {
            NodeSites.remove(level, node);
            level.setBlock(node, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        }
    }
}
