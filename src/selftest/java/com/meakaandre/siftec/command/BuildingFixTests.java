package com.meakaandre.siftec.command;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.block.NodeBlock;
import com.meakaandre.siftec.building.BasinPace;
import com.meakaandre.siftec.building.BuildingDef;
import com.meakaandre.siftec.building.BuildingLogic;
import com.meakaandre.siftec.building.BuildingPower;
import com.meakaandre.siftec.building.BuildingTemplate;
import com.meakaandre.siftec.building.BuildingTests;
import com.meakaandre.siftec.building.Buildings;
import com.meakaandre.siftec.building.ControllerBlockEntity;
import com.meakaandre.siftec.building.ExtractorLogic;
import com.meakaandre.siftec.building.Placement;
import com.meakaandre.siftec.building.PortBlock;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.company.CompanyData;
import com.meakaandre.siftec.hub.HubBlockEntity;
import com.meakaandre.siftec.node.NodeLook;
import com.meakaandre.siftec.node.NodeType;
import com.meakaandre.siftec.node.Purity;
import com.meakaandre.siftec.power.PowerTests;
import com.meakaandre.siftec.prefab.Prefabs;
import com.meakaandre.siftec.prefab.Templates;
import com.meakaandre.siftec.registry.ModBlocks;
import com.mojang.brigadier.context.CommandContext;
import com.zurrtum.create.content.kinetics.base.KineticBlockEntity;
import com.zurrtum.create.content.kinetics.crank.HandCrankBlockEntity;
import com.zurrtum.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.zurrtum.create.content.processing.basin.BasinBlockEntity;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /siftec selftest buildingfix}: the building-mechanics fixes, on a test site of their own. Every check prints
 * "SELFTEST buildingfix"; failures say FAIL; the last line counts them.
 * <ul>
 * <li>8: a building's controller has no item storage on any side (a hopper beside it gives it nothing); its ports still
 * reach it, and funnels and hoppers reach only a building's ports, never its machines;</li>
 * <li>9: the HUB's Craft Station takes no rotation from a motor outside it, and its own hand crank still turns it;</li>
 * <li>10: the Manufacturer's Core Mixer and its assembly line keep separate times: a Core goes onto the line while the
 * Mixer waits out its own recipe, and the line's item does not hold the Mixer back;</li>
 * <li>13: an idle Constructor draws next to nothing from its grid, and its full SU once it has work;</li>
 * <li>18: a running Resource Well Pressurizer takes at most 8 Extractors of its company; the ninth gives nothing and its
 * status says why.</li>
 * </ul>
 */
public final class BuildingFixTests {
    private static final int X = 12000, Y = 200, Z = -12000;
    private static final String COMPANY = "selftest_buildingfix";

    private record Later(long at, Runnable run) {
    }

    private static final List<Later> LATER = new ArrayList<>();
    private static int passed, failed, pending;

    private BuildingFixTests() {
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
                .then(Commands.literal("selftest").then(Commands.literal("buildingfix").executes(BuildingFixTests::run)))));
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static void later(ServerLevel level, int ticks, Runnable run) {
        pending++;
        LATER.add(new Later(level.getServer().overworld().getGameTime() + ticks, () -> {
            try {
                run.run();
            } catch (RuntimeException e) {
                Siftec.LOGGER.error("SIFTEC: selftest buildingfix check threw", e);
                failed++;
                Siftec.LOGGER.info("SELFTEST buildingfix FAIL a delayed check threw: {}", e.toString());
            }
            pending--;
        }));
    }

    private static void report(CommandSourceStack source, String text) {
        String line = "SELFTEST buildingfix " + text;
        Siftec.LOGGER.info(line);
        source.sendSuccess(() -> Component.literal(line), false);
    }

    private static void expect(CommandSourceStack source, String what, boolean ok, String detail) {
        if (ok) passed++;
        else failed++;
        report(source, (ok ? "PASS " : "FAIL ") + what + (detail.isEmpty() ? "" : " (" + detail + ")"));
    }

    /** The test company, with the milestones its buildings and recipes need. */
    private static Company company(ServerLevel level) {
        CompanyData data = CompanyData.get(level.getServer());
        Company c = data.companies().get(COMPANY);
        if (c == null) {
            c = new Company();
            c.id = COMPANY;
            c.name = COMPANY;
            data.companies().put(COMPANY, c);
        }
        c.done.addAll(List.of("hub_upgrade_1", "hub_upgrade_2", "hub_upgrade_3", "industrial_manufacturing", "advanced_aluminum_production"));
        data.setDirty();
        return c;
    }

    private static Block create(String id) {
        return BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath("create", id));
    }

    private static Item item(String id) {
        return BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
    }

    /** A building put down for the test company as in creative (no cost); null (and said why) if it could not go. */
    private static @Nullable ControllerBlockEntity place(CommandSourceStack source, ServerLevel level, Company company, String id, BlockPos anchor) {
        BuildingDef def = Buildings.def(id);
        BuildingTemplate t = def == null ? null : BuildingTemplate.of(def);
        if (t == null) {
            expect(source, id + " exists", false, "");
            return null;
        }
        Placement p = new Placement(def, anchor, Direction.NORTH, false);
        Buildings.Check check = Buildings.place(level, p, company, null, true);
        ControllerBlockEntity c = level.getBlockEntity(p.at(t.controller)) instanceof ControllerBlockEntity ce ? ce : null;
        if (!check.ok() || c == null) expect(source, id + " goes down at " + anchor.toShortString(), false, check.message().getString());
        return c;
    }

    /** An Alien Power Augmenter (500 MW, no fuel) behind a building, wired to it. */
    private static boolean power(CommandSourceStack source, ServerLevel level, @Nullable ControllerBlockEntity c, BlockPos at) {
        ControllerBlockEntity aug = PowerTests.build(level, source, "alien_power_augmenter", at, Direction.NORTH);
        return c != null && aug != null && PowerTests.wire(PowerTests.connector(level, aug), PowerTests.connector(level, c));
    }

    private static Direction toward(BlockPos from, BlockPos to) {
        BlockPos d = to.subtract(from);
        return Direction.getNearest(d.getX(), d.getY(), d.getZ(), Direction.UP);
    }

    // ------------------------------------------------------------------------------------------------ the test

    private static int run(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        passed = failed = pending = 0;
        BuildingTemplate.forget();
        BuildingTests.platform(level, X - 8, Z - 50, X + 140, Z + 56, Y, 32);
        later(level, 10, () -> {
            Company company = company(level);
            run(source, "8 ports only", () -> portsOnly(source, level, company));
            run(source, "9 craft station", () -> craftStation(source, level, company));
            run(source, "10 manufacturer timers", () -> manufacturer(source, level, company));
            run(source, "13 idle draw", () -> idleDraw(source, level, company));
            run(source, "18 resource well", () -> well(source, level, company));
            finish(source, level);
        });
        report(source, "test site ready; the checks report over the next few seconds");
        return 1;
    }

    private static void run(CommandSourceStack source, String part, Runnable checks) {
        try {
            checks.run();
        } catch (RuntimeException e) {
            Siftec.LOGGER.error("SIFTEC: selftest buildingfix {} threw", part, e);
            expect(source, part + " runs to the end", false, e.toString());
        }
    }

    private static void finish(CommandSourceStack source, ServerLevel level) {
        if (pending > 0) {
            LATER.add(new Later(level.getServer().overworld().getGameTime() + 10, () -> finish(source, level)));
            return;
        }
        report(source, "done: " + passed + " passed, " + failed + " failed");
    }

    // ------------------------------------------------------------------------------------------------ 8: ports only

    private static void portsOnly(CommandSourceStack source, ServerLevel level, Company company) {
        // the Smelter: its controller holds the ore and ingots
        ControllerBlockEntity sm = place(source, level, company, "smelter", new BlockPos(X + 4, Y, Z + 20));
        Placement p = sm == null ? null : sm.placement();
        if (sm == null || p == null) return;
        BlockPos controller = sm.getBlockPos();
        int sides = 0;
        for (Direction d : Direction.values()) if (ItemStorage.SIDED.find(level, controller, d) != null) sides++;
        if (ItemStorage.SIDED.find(level, controller, null) != null) sides++;
        expect(source, "8: a building's controller has no item storage on any side", sides == 0, sides + " sides give one");
        // a hopper right beside the controller, pointing into it, holding raw iron the Smelter would take
        BlockPos hopperAt = null;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos at = controller.relative(d);
            if (level.getBlockState(at).isAir() && !Buildings.protectedAt(level, at)) {
                hopperAt = at;
                break;
            }
        }
        if (hopperAt == null) {
            expect(source, "8: there is room for a hopper beside the Smelter's controller", false, "");
        } else {
            BlockPos h = hopperAt;
            level.setBlock(h, Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, toward(h, controller)), Block.UPDATE_ALL);
            if (level.getBlockEntity(h) instanceof HopperBlockEntity hopper) hopper.setItem(0, new ItemStack(Items.RAW_IRON, 8));
            later(level, 40, () -> {
                int left = level.getBlockEntity(h) instanceof HopperBlockEntity hopper ? hopper.getItem(0).getCount() : -1;
                expect(source, "8: a hopper pointed at the controller puts nothing into the building", left == 8 && sm.input.getItem(0).getCount() <= 4,
                    left + " raw iron left in the hopper, controller input " + sm.input.getItem(0));
                level.setBlock(h, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            });
        }
        // its input port still reaches the controller
        BlockPos portAt = p.at(0, 0, BuildingTemplate.of(p.def()).depth - 1);
        BlockState ps = level.getBlockState(portAt);
        Storage<ItemVariant> port = ps.getBlock() instanceof PortBlock ? ItemStorage.SIDED.find(level, portAt, ps.getValue(PortBlock.FACING)) : null;
        long in = 0;
        if (port != null) {
            try (Transaction tx = Transaction.openOuter()) {
                in = port.insert(ItemVariant.of(Items.RAW_IRON), 4, tx);
                tx.commit();
            }
        }
        expect(source, "8: the Smelter's input port still puts ore into its controller", in == 4 && sm.input.getItem(0).is(Items.RAW_IRON),
            "port " + (port != null) + ", " + in + " went in");
        // funnels and hoppers (Buildings.sealed) reach a building's ports only, never its machines from outside
        ControllerBlockEntity con = place(source, level, company, "constructor", new BlockPos(X + 4, Y, Z + 40));
        BlockPos basin = con == null ? null : BuildingLogic.cell(con, create("basin"));
        BlockPos conPort = con == null || con.placement() == null ? null : con.placement().at(0, 0, 0);
        BlockPos press = con == null ? null : BuildingLogic.cell(con, create("mechanical_press"));
        BlockPos outside = conPort == null ? null : conPort.relative(Direction.SOUTH, 2);
        expect(source, "8: from outside, only a building's ports can be reached; its own machines may reach each other",
            basin != null && press != null && outside != null && Buildings.sealed(level, outside, basin) && Buildings.sealed(level, outside, con.getBlockPos())
                && !Buildings.sealed(level, outside, conPort) && !Buildings.sealed(level, press, basin),
            "basin " + basin + ", port " + conPort);
    }

    // ------------------------------------------------------------------------------------------------ 9: craft station

    private static void craftStation(CommandSourceStack source, ServerLevel level, Company company) {
        Prefabs.Spot spot = new Prefabs.Spot(Prefabs.Kind.HUB, new BlockPos(X + 70, Y, Z), Direction.SOUTH);
        if (level.getBlockEntity(spot.terminal()) instanceof HubBlockEntity old) Prefabs.dismantle(level, old);
        Prefabs.Check placed = Prefabs.place(level, spot, company, null);
        HubBlockEntity hub = level.getBlockEntity(spot.terminal()) instanceof HubBlockEntity h ? h : null;
        if (!placed.ok() || hub == null) {
            expect(source, "9: the HUB goes down", false, placed.message().getString());
            return;
        }
        BlockPos press = spot.at(Templates.PRESS[0], Templates.PRESS[1], Templates.PRESS[2]);
        BlockPos crank = spot.at(Templates.PRESS_CRANK[0], Templates.PRESS_CRANK[1], Templates.PRESS_CRANK[2]);
        // a Creative Motor on the Press's free shaft end, the side away from its crank
        BlockPos motorAt = press.relative(toward(crank, press));
        boolean free = level.getBlockState(motorAt).isAir();
        level.setBlock(motorAt, create("creative_motor").defaultBlockState().setValue(BlockStateProperties.FACING, toward(motorAt, press)), Block.UPDATE_ALL);
        if (level.getBlockEntity(motorAt) instanceof CreativeMotorBlockEntity m) m.generatedSpeed.setValue(64);
        later(level, 30, () -> {
            float motor = level.getBlockEntity(motorAt) instanceof KineticBlockEntity k ? k.getSpeed() : -1;
            float speed = level.getBlockEntity(press) instanceof KineticBlockEntity k ? k.getSpeed() : -1;
            expect(source, "9: a motor against the Craft Station's Press does not turn it (it is hand-cranked only)", free && motor != 0 && speed == 0,
                "spot free " + free + ", motor " + motor + " RPM, press " + speed + " RPM");
            if (level.getBlockEntity(crank) instanceof HandCrankBlockEntity c) c.turn(false);
            later(level, 3, () -> {
                float cranked = level.getBlockEntity(press) instanceof KineticBlockEntity k ? k.getSpeed() : 0;
                expect(source, "9: its own hand crank still turns it", cranked != 0, cranked + " RPM");
                level.setBlock(motorAt, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                if (level.getBlockEntity(spot.terminal()) instanceof HubBlockEntity h) Prefabs.dismantle(level, h);
            });
        });
    }

    // ------------------------------------------------------------------------------------------------ 10: manufacturer

    private static void manufacturer(CommandSourceStack source, ServerLevel level, Company company) {
        ControllerBlockEntity mc = place(source, level, company, "manufacturer", new BlockPos(X + 40, Y, Z + 20));
        if (mc == null) return;
        boolean wired = power(source, level, mc, new BlockPos(X + 40, Y, Z + 42));
        BuildingLogic.setFilter(level, mc, new ItemStack(item("siftec:computer")));
        BlockPos brass = BuildingLogic.cell(mc, ModBlocks.BRASS_BASIN.get());
        BlockPos mixer = BuildingLogic.cell(mc, create("mechanical_mixer"));
        Item core = item("siftec:computer_core");
        later(level, 40, () -> {
            if (brass == null || !(level.getBlockEntity(brass) instanceof BasinBlockEntity basin) || mixer == null || !(level.getBlockEntity(mixer) instanceof KineticBlockEntity mix)) {
                expect(source, "10: the Manufacturer has its Brass Basin and Mixer", false, "wired " + wired);
                return;
            }
            // the Mixer has just started a Core (its own 24 s are running); a finished Core waits in the Basin
            long now = level.getGameTime(), mixerUntil = now + 100_000;
            mc.nextCraft = mixerUntil;
            mc.lineNext = 0;
            basin.itemCapability.setItem(9, new ItemStack(core));
            basin.itemCapability.setChanged();
            later(level, 5, () -> {
                boolean onLine = false;
                for (ItemStack s : mc.line) onLine |= s.is(core);
                long t = level.getGameTime();
                expect(source, "10: a Core goes onto the line while the Mixer waits out its own recipe", onLine && mc.powered,
                    "line " + mc.line + ", powered " + mc.powered + ", " + BuildingLogic.status(level, mc).getString());
                expect(source, "10: the line keeps its own time (24 s for a Computer) and leaves the Mixer's alone",
                    mc.nextCraft == mixerUntil && mc.lineNext > t && mc.lineNext - t <= 480,
                    "mixer until +" + (mc.nextCraft - t) + ", line until +" + (mc.lineNext - t));
                mc.nextCraft = 0;
                expect(source, "10: with its own recipe done, the Mixer may start the next Core while the line is still busy",
                    !BasinPace.blocked(mix) && mc.lineNext > t, "");
            });
        });
    }

    // ------------------------------------------------------------------------------------------------ 13: idle draw

    private static void idleDraw(CommandSourceStack source, ServerLevel level, Company company) {
        ControllerBlockEntity cc = place(source, level, company, "constructor", new BlockPos(X + 20, Y, Z + 20));
        if (cc == null) return;
        boolean wired = power(source, level, cc, new BlockPos(X + 20, Y, Z + 34));
        BuildingLogic.setFilter(level, cc, new ItemStack(item("create:iron_sheet")));
        later(level, 60, () -> {
            double idle = cc.grid == null ? -1 : cc.grid.demand;
            expect(source, "13: a Constructor with nothing to work on draws next to nothing from its grid", wired && cc.powered && idle >= 0 && idle <= BuildingPower.STANDBY_SU,
                "wired " + wired + ", powered " + cc.powered + ", grid demand " + idle + " SU; " + BuildingLogic.status(level, cc).getString());
            BlockPos basin = BuildingLogic.cell(cc, create("basin"));
            if (basin != null && level.getBlockEntity(basin) instanceof BasinBlockEntity b) {
                b.itemCapability.setItem(0, new ItemStack(Items.IRON_INGOT, 30));
                b.itemCapability.setChanged();
            }
            later(level, 20, () -> {
                double working = cc.grid == null ? -1 : cc.grid.demand;
                BuildingDef def = cc.def();
                expect(source, "13: given ingots, it draws its full SU", def != null && working == def.su() && cc.powered && !cc.idle,
                    "grid demand " + working + " SU; " + BuildingLogic.status(level, cc).getString());
            });
        });
    }

    // ------------------------------------------------------------------------------------------------ 18: resource well

    private static void well(CommandSourceStack source, ServerLevel level, Company company) {
        BuildingDef def = Buildings.def("resource_well_pressurizer");
        if (def == null) return;
        Placement pp = new Placement(def, new BlockPos(X + 110, Y, Z - 20), Direction.NORTH, false);
        BlockPos node = pp.at(0, 0, 2);
        NodeLook look = NodeLook.of(NodeType.NITROGEN);
        level.setBlock(node.below(), look.patchStone(), Block.UPDATE_ALL);
        level.setBlock(node, ModBlocks.NODES.get(look.id()).get().defaultBlockState().setValue(NodeBlock.PURITY, Purity.NORMAL), Block.UPDATE_ALL);
        ControllerBlockEntity pr = place(source, level, company, "resource_well_pressurizer", pp.anchor());
        if (pr == null) return;
        boolean wired = power(source, level, pr, new BlockPos(X + 110, Y, Z - 6));
        // nine Extractors of the company within 32 blocks of it
        List<ControllerBlockEntity> extractors = new ArrayList<>();
        for (int i = 0; i < ExtractorLogic.WELL_EXTRACTORS + 1; i++) {
            ControllerBlockEntity e = place(source, level, company, "resource_well_extractor", new BlockPos(X + 90 + 5 * i, Y, Z - 40));
            if (e != null) extractors.add(e);
        }
        later(level, 80, () -> {
            int making = 0, full = 0;
            String why = "";
            StringBuilder all = new StringBuilder();
            for (ControllerBlockEntity e : extractors) {
                boolean within = e.getBlockPos().distSqr(pr.getBlockPos()) <= (double) ExtractorLogic.WELL_RANGE * ExtractorLogic.WELL_RANGE;
                if (e.mined > 0 && e.problem.isEmpty()) making++;
                if (e.problem.equals("siftec.building.well_full") && e.mined == 0) {
                    full++;
                    why = BuildingLogic.status(level, e).getString();
                }
                all.append(within ? "" : " (out of range)").append(" ").append(e.mined).append(" mB ").append(e.problem.isEmpty() ? "ok" : e.problem);
            }
            expect(source, "18: a running Pressurizer feeds 8 Extractors of its company; the ninth gives nothing",
                wired && pr.powered && extractors.size() == 9 && making == 8 && full == 1, "pressurizer powered " + pr.powered + ";" + all);
            Component message = Component.translatable("siftec.building.well_full");
            expect(source, "18: the ninth Extractor's status says why", !why.isEmpty() && why.contains(message.getString()), why);
        });
    }
}
