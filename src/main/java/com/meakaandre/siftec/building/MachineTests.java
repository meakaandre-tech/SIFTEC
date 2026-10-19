package com.meakaandre.siftec.building;

import com.google.gson.JsonElement;
import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.company.CompanyData;
import com.meakaandre.siftec.geyser.GeyserPurity;
import com.meakaandre.siftec.hub.Milestones;
import com.meakaandre.siftec.node.Purity;
import com.meakaandre.siftec.power.Power;
import com.meakaandre.siftec.power.PowerTests;
import com.mojang.brigadier.context.CommandContext;
import com.zurrtum.create.content.fluids.tank.FluidTankBlockEntity;
import com.zurrtum.create.content.kinetics.base.KineticBlockEntity;
import com.zurrtum.create.content.kinetics.crusher.CrushingWheelControllerBlock;
import com.zurrtum.create.content.processing.burner.BlazeBurnerBlockEntity;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import org.antarcticgardens.cna.content.motor.MotorBlockEntity;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/**
 * {@code /siftec selftest machines}: every building whose work its machines do, checked on its machines. Every line
 * prints "SELFTEST machines"; failures say FAIL; the last line counts them.
 * <ul>
 * <li>Biomass Burner, Coal Generator: the Blaze Burners are lit, the boiler's Steam Engines turn the New Age coil, and on
 * a full load (Power Storage charging) they burn Satisfactory's fuel (4 Solid Biofuel, 15 Coal a minute) and the Coal
 * Generator its water (45 m3, 1,800 mB a minute) for 30 MW and 75 MW (6,144 and 15,360 SU);</li>
 * <li>Fuel Generator: Diesel Generators' engine turns the coil at 256 RPM; on a full load it burns 20 m3 of Fuel a minute
 * (800 mB) for 250 MW (51,200 SU); on 100 MW of load it burns Turbofuel for those 100 MW only (120 mB a minute);</li>
 * <li>Nuclear Power Plant: New Age's reactor heats the boilers, 18 heat levels each, whose Steam Engines turn the coils at
 * 256 RPM for 2,500 MW (512,000 SU); its rods burn at 0.2 a minute and its water at 240 m3 (9,600 mB) a minute whatever
 * the load, and every rod leaves 50 Uranium Waste;</li>
 * <li>Geothermal Generator: on an impure, a normal and a pure geyser, what its coils make swings between Satisfactory's
 * least and most (50-150, 100-300, 200-600 MW) around 100, 200, 400 MW on average;</li>
 * <li>Particle Accelerator (Diamonds): the Encased Fan blows each Core up into the Crushing Wheels, which crush it into the
 * Diamond: 30 a minute; with the wheels stopped, nothing comes out; its power rises from 250 to 750 MW over each cycle;</li>
 * <li>Converter: Iron Ore (Limestone), the Deployer adding the Reanimated SAM and the fan haunting through soul fire, 120
 * a minute; with the fan stopped nothing comes out; Dark Matter Residue comes out of its Item Drain, 4,000 mB a minute,
 * with its one Converter Cell going round;</li>
 * <li>a building's machines are kept whole: a Steam Engine taken away is put back; a building from before its shape
 * changed is rebuilt to the current one; a block in the way leaves it making nothing (no power in code) until it is
 * cleared.</li>
 * </ul>
 * {@code /siftec selftest machines restart}: after a restart, the same buildings run on their machines again.
 */
public final class MachineTests {
    private static final int X = 9000, Y = 200, Z = 9000;
    private static final String COMPANY = "selftest_machines";
    private static final int WARMUP = 600, MINUTES = 2, MINUTE = 1200;

    private record Later(long at, Runnable run) {
    }

    private static final List<Later> LATER = new ArrayList<>();
    /** Things done every tick while the test runs (feeding, emptying, sampling); each says when it is done. */
    private static final List<BooleanSupplier> EVERY_TICK = new ArrayList<>();
    private static int passed, failed;

    private MachineTests() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            long now = server.overworld().getGameTime();
            for (Later l : new ArrayList<>(LATER)) {
                if (l.at > now) continue;
                LATER.remove(l);
                try {
                    l.run.run();
                } catch (RuntimeException e) {
                    Siftec.LOGGER.error("SIFTEC: selftest machines check threw", e);
                    failed++;
                    Siftec.LOGGER.info("SELFTEST machines FAIL a delayed check threw: {}", e.toString());
                }
            }
            for (BooleanSupplier s : new ArrayList<>(EVERY_TICK)) {
                try {
                    if (s.getAsBoolean()) EVERY_TICK.remove(s);
                } catch (RuntimeException e) {
                    Siftec.LOGGER.error("SIFTEC: selftest machines feeder threw", e);
                    EVERY_TICK.remove(s);
                }
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            LATER.clear();
            EVERY_TICK.clear();
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
            Commands.literal("siftec").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("selftest").then(Commands.literal("machines").executes(c -> run(c, false))
                    .then(Commands.literal("restart").executes(c -> run(c, true)))))));
    }

    // ------------------------------------------------------------------------------------------------ reporting

    private static void later(ServerLevel level, int ticks, Runnable run) {
        LATER.add(new Later(level.getServer().overworld().getGameTime() + ticks, run));
    }

    private static void report(CommandSourceStack source, String text) {
        String line = "SELFTEST machines " + text;
        Siftec.LOGGER.info(line);
        source.sendSuccess(() -> Component.literal(line), false);
    }

    private static void expect(CommandSourceStack source, String what, boolean ok, String detail) {
        if (ok) passed++;
        else failed++;
        report(source, (ok ? "PASS " : "FAIL ") + what + (detail.isEmpty() ? "" : " (" + detail + ")"));
    }

    private static void near(CommandSourceStack source, String what, double got, double want, double share) {
        expect(source, what, Math.abs(got - want) <= Math.abs(want) * share, String.format(Locale.ROOT, "%.1f, wanted %.1f within %.0f%%", got, want, share * 100));
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static Item item(String id) {
        return BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
    }

    private static Fluid fluid(String id) {
        return BuiltInRegistries.FLUID.getValue(Identifier.parse(id));
    }

    static Block block(String id) {
        return BuiltInRegistries.BLOCK.getValue(Identifier.parse(id));
    }

    private static Company company(ServerLevel level) {
        CompanyData data = CompanyData.get(level.getServer());
        Company c = data.companies().get(COMPANY);
        if (c == null) {
            c = new Company();
            c.id = COMPANY;
            c.name = "Selftest Machines";
            data.companies().put(COMPANY, c);
        }
        var raw = Milestones.raw();
        for (String key : new String[]{"milestones", "milestones_mam"}) {
            if (raw == null || !raw.has(key)) continue;
            for (JsonElement m : raw.getAsJsonArray(key)) c.done.add(m.getAsJsonObject().get("id").getAsString());
        }
        for (int i = 1; i <= 5; i++) c.done.add("phase_" + i);
        Companies.save(level.getServer());
        return c;
    }

    private static Placement at(String id, BlockPos anchor) {
        return new Placement(Buildings.def(id), anchor, Direction.NORTH, false);
    }

    private static @Nullable ControllerBlockEntity place(CommandSourceStack source, ServerLevel level, Company company, String id, BlockPos anchor) {
        BuildingDef def = Buildings.def(id);
        BuildingTemplate t = def == null ? null : BuildingTemplate.of(def);
        if (t == null) {
            expect(source, id + " exists", false, "");
            return null;
        }
        Placement p = at(id, anchor);
        Buildings.Check check = Buildings.place(level, p, company, null, true);
        ControllerBlockEntity c = level.getBlockEntity(p.at(t.controller)) instanceof ControllerBlockEntity ce ? ce : null;
        if (!check.ok() || c == null) {
            expect(source, id + " goes down at " + anchor.toShortString(), false, check.message().getString()
                + (check.blocked.isEmpty() ? "" : " first blocked " + check.blocked.getFirst().toShortString() + " " + level.getBlockState(check.blocked.getFirst())));
        }
        return c;
    }

    private static @Nullable ControllerBlockEntity controller(ServerLevel level, String id, BlockPos anchor) {
        BuildingDef def = Buildings.def(id);
        BuildingTemplate t = def == null ? null : BuildingTemplate.of(def);
        return t != null && level.getBlockEntity(at(id, anchor).at(t.controller)) instanceof ControllerBlockEntity c ? c : null;
    }

    /** Power Storages behind a building (100 MW of charging each), wired to it: a load the test knows. */
    private static void storages(CommandSourceStack source, ServerLevel level, Company company, @Nullable ControllerBlockEntity c, int n) {
        Placement p = c == null ? null : c.placement();
        BuildingTemplate t = p == null ? null : BuildingTemplate.of(p.def());
        if (t == null) return;
        int wired = 0;
        for (int i = 0; i < n; i++) {
            ControllerBlockEntity s = place(source, level, company, "power_storage", p.at(-6 + 6 * i, 0, t.depth + 4));
            if (PowerTests.wire(PowerTests.connector(level, s), PowerTests.connector(level, c))) wired++;
        }
        if (wired != n) expect(source, p.def().id() + " is wired to " + n + " Power Storage", false, wired + " wired");
    }

    /** Alien Power Augmenters behind a building (500 MW each), each wired to it. */
    private static void augmenters(CommandSourceStack source, ServerLevel level, Company company, @Nullable ControllerBlockEntity c, int n) {
        Placement p = c == null ? null : c.placement();
        BuildingTemplate t = p == null ? null : BuildingTemplate.of(p.def());
        if (t == null) return;
        int wired = 0;
        for (int i = 0; i < n; i++) {
            ControllerBlockEntity a = place(source, level, company, "alien_power_augmenter", p.at(-8 + 12 * i, 0, t.depth + 6));
            if (PowerTests.wire(PowerTests.connector(level, a), PowerTests.connector(level, c))) wired++;
        }
        if (wired != n) expect(source, p.def().id() + " is wired to " + n + " Alien Power Augmenters", false, wired + " wired");
    }

    private record Port(BlockPos pos, Direction side) {
    }

    /** The building's n-th port of a kind, and the side things go in or out through. */
    private static @Nullable Port port(ServerLevel level, @Nullable ControllerBlockEntity c, PortBlock.Kind kind, int n) {
        Placement p = c == null ? null : c.placement();
        BuildingTemplate t = p == null ? null : BuildingTemplate.of(p.def());
        if (t == null) return null;
        for (BuildingTemplate.Cell cell : t.members) {
            if (!(cell.state().getBlock() instanceof PortBlock) || cell.state().getValue(PortBlock.KIND) != kind) continue;
            if (n-- > 0) continue;
            BlockPos pos = p.at(cell);
            BlockState state = level.getBlockState(pos);
            return new Port(pos, state.hasProperty(PortBlock.FACING) ? state.getValue(PortBlock.FACING) : Direction.DOWN);
        }
        return null;
    }

    /** Puts items in through a port, as a belt would; the count that went in. */
    private static long feed(ServerLevel level, @Nullable Port port, Item item, int most) {
        Storage<ItemVariant> into = port == null ? null : ItemStorage.SIDED.find(level, port.pos(), port.side());
        if (into == null) return 0;
        try (Transaction tx = Transaction.openOuter()) {
            long in = into.insert(ItemVariant.of(item), most, tx);
            tx.commit();
            return in;
        }
    }

    /** Pipes a fluid in through a port; the droplets that went in. */
    private static long pipe(ServerLevel level, @Nullable Port port, Fluid fluid, long mb) {
        Storage<FluidVariant> into = port == null ? null : FluidStorage.SIDED.find(level, port.pos(), port.side());
        if (into == null) return 0;
        try (Transaction tx = Transaction.openOuter()) {
            long in = into.insert(FluidVariant.of(fluid), mb * (FluidConstants.BUCKET / 1000), tx);
            tx.commit();
            return in;
        }
    }

    /** Pumps everything out through a fluid port; the droplets that came out. */
    private static long pump(ServerLevel level, @Nullable Port port) {
        Storage<FluidVariant> from = port == null ? null : FluidStorage.SIDED.find(level, port.pos(), port.side());
        if (from == null) return 0;
        long out = 0;
        try (Transaction tx = Transaction.openOuter()) {
            for (StorageView<FluidVariant> v : from) {
                if (v.isResourceBlank() || v.getAmount() <= 0) continue;
                out += v.extract(v.getResource(), v.getAmount(), tx);
            }
            tx.commit();
        }
        return out;
    }

    /** Takes the building's output, as a belt from its output port would; how many of the item it held. */
    private static long empty(@Nullable ControllerBlockEntity c, Item item) {
        if (c == null) return 0;
        ItemStack s = c.output.getItem(0);
        if (s.isEmpty()) return 0;
        long n = s.is(item) ? s.getCount() : 0;
        c.output.setItem(0, ItemStack.EMPTY);
        c.output.setChanged();
        return n;
    }

    private static float speed(ServerLevel level, @Nullable BlockPos pos) {
        return pos != null && level.getBlockEntity(pos) instanceof KineticBlockEntity k ? k.getSpeed() : 0;
    }

    private static @Nullable BlockPos cell(@Nullable ControllerBlockEntity c, String id) {
        return BuildingLogic.cell(c, block(id));
    }

    private static double su(double mw) {
        return Power.su(mw);
    }

    /** What its coils make now: what the grid was offered on its last balance (the brushes may already be emptied). */
    private static double offered(ServerLevel level, @Nullable ControllerBlockEntity c) {
        return c == null ? 0 : Math.max(c.offered, Generators.offered(level, c));
    }

    private static String status(ServerLevel level, @Nullable ControllerBlockEntity c) {
        return c == null ? "missing" : BuildingLogic.status(level, c).getString();
    }

    /** The New Age motors next to a block, and their speeds. */
    private static List<MotorBlockEntity> motorsBy(ServerLevel level, @Nullable BlockPos pos) {
        List<MotorBlockEntity> out = new ArrayList<>();
        if (pos == null) return out;
        for (Direction d : Direction.values()) if (level.getBlockEntity(pos.relative(d)) instanceof MotorBlockEntity m && m.speedBehavior != null) out.add(m);
        return out;
    }

    private static List<int[]> stop(List<MotorBlockEntity> motors) {
        List<int[]> was = new ArrayList<>();
        for (MotorBlockEntity m : motors) {
            was.add(new int[]{m.speedBehavior.getValue()});
            m.speedBehavior.setValue(0);
        }
        return was;
    }

    private static void restart(List<MotorBlockEntity> motors, List<int[]> was) {
        for (int i = 0; i < motors.size() && i < was.size(); i++) motors.get(i).speedBehavior.setValue(was.get(i)[0]);
    }

    /** What a Nuclear Power Plant's acceptors hold and its rods' fuel, its input, output, water and fuse (for the log). */
    private static String reactorState(ServerLevel level, @Nullable ControllerBlockEntity c) {
        if (c == null) return "missing";
        StringBuilder b = new StringBuilder("acceptors");
        for (BlockPos pos : BuildingLogic.cells(c, block("create_new_age:reactor_fuel_acceptor"))) {
            if (level.getBlockEntity(pos) instanceof org.antarcticgardens.cna.content.nuclear.reactor.fuelacceptor.ReactorFuelAcceptorBlockEntity a) b.append(' ').append(a.container.getItem(0));
        }
        b.append("; rod fuel ").append(Generators.rodFuel(level, c)).append("; input ").append(c.input.getItem(0)).append("; output ").append(c.output.getItem(0))
            .append("; tripped ").append(c.tripped).append("; problem ").append(c.machineProblem);
        return b.toString();
    }

    /** Fuel in a Fuel Generator's engine, in droplets. */
    private static long engineFuel(ServerLevel level, @Nullable ControllerBlockEntity c) {
        BlockPos at = cell(c, "createdieselgenerators:diesel_engine");
        return at != null && level.getBlockEntity(at) instanceof EngineControl e ? e.siftec$fuel().getAmount() : -1;
    }

    private static long mb(long droplets) {
        return droplets / (FluidConstants.BUCKET / 1000);
    }

    // ------------------------------------------------------------------------------------------------ where things stand

    private static final BlockPos BIOMASS = new BlockPos(X, Y, Z), COAL = new BlockPos(X + 24, Y, Z), DIESEL = new BlockPos(X + 48, Y, Z),
        TURBO = new BlockPos(X + 72, Y, Z), NUCLEAR = new BlockPos(X + 110, Y, Z), ACCELERATOR = new BlockPos(X + 160, Y, Z),
        CONVERTER = new BlockPos(X + 200, Y, Z), RESIDUE = new BlockPos(X + 230, Y, Z), REPAIR = new BlockPos(X + 260, Y, Z);
    private static final int GEO_Z = Z + 70;

    /** Spots for Geothermal Generators along a row whose geysers are impure, normal and pure (in that order). */
    private static List<BlockPos> geysers(ServerLevel level) {
        BuildingDef def = Buildings.def("geothermal_generator");
        BlockPos[] found = new BlockPos[3];
        if (def == null || def.vent() == null) return List.of();
        for (int i = 0; i < 400 && (found[0] == null || found[1] == null || found[2] == null); i++) {
            BlockPos anchor = new BlockPos(X + i * 14, Y, GEO_Z);
            Purity purity = GeyserPurity.of(level, vent(def, anchor).below(2));
            int k = purity == Purity.IMPURE ? 0 : purity == Purity.NORMAL ? 1 : 2;
            if (found[k] == null) found[k] = anchor;
        }
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos b : found) if (b != null) out.add(b);
        return out.size() == 3 ? out : List.of();
    }

    private static BlockPos vent(BuildingDef def, BlockPos anchor) {
        int[] v = def.vent();
        return at(def.id(), anchor).at(v[0], v[1], v[2]);
    }

    // ------------------------------------------------------------------------------------------------ the test

    private static int run(CommandContext<CommandSourceStack> context, boolean restart) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        passed = failed = 0;
        if (restart) {
            afterRestart(source, level);
            return 1;
        }
        BuildingTemplate.forget();
        // facing north, the buildings stand north of their anchors, and their storages and augmenters behind them
        BuildingTests.platform(level, X - 16, Z - 52, X + 290, Z + 8, Y, 22);
        List<BlockPos> geo = geysers(level);
        for (BlockPos g : geo) BuildingTests.platform(level, g.getX() - 10, g.getZ() - 22, g.getX() + 10, g.getZ() + 4, Y, 14);
        later(level, 10, () -> setUp(source, level, geo));
        report(source, "test site ready at " + X + " " + Y + " " + Z + "; geysers at " + geo.stream().map(BlockPos::toShortString).toList()
            + "; the checks report over the next three minutes");
        return 1;
    }

    private static void setUp(CommandSourceStack source, ServerLevel level, List<BlockPos> geo) {
        Company company = company(level);
        expect(source, "an impure, a normal and a pure geyser are found for the Geothermal Generators", geo.size() == 3, geo.size() + " found");
        // ---- generators
        ControllerBlockEntity bio = place(source, level, company, "biomass_burner", BIOMASS);
        ControllerBlockEntity coal = place(source, level, company, "coal_generator", COAL);
        ControllerBlockEntity diesel = place(source, level, company, "fuel_generator", DIESEL);
        ControllerBlockEntity turbo = place(source, level, company, "fuel_generator", TURBO);
        ControllerBlockEntity nuke = place(source, level, company, "nuclear_power_plant", NUCLEAR);
        ControllerBlockEntity repair = place(source, level, company, "coal_generator", REPAIR);
        storages(source, level, company, bio, 1);
        storages(source, level, company, coal, 1);
        storages(source, level, company, diesel, 3);
        storages(source, level, company, turbo, 1);
        storages(source, level, company, nuke, 1);
        storages(source, level, company, repair, 1);
        if (bio != null) bio.input.setItem(0, new ItemStack(item("siftec:solid_biofuel"), 64));
        if (coal != null) coal.input.setItem(0, new ItemStack(Items.COAL, 64));
        if (repair != null) repair.input.setItem(0, new ItemStack(Items.COAL, 64));
        if (nuke != null) nuke.input.setItem(0, new ItemStack(item("siftec:uranium_fuel_rod"), 12));
        List<ControllerBlockEntity> geos = new ArrayList<>();
        BuildingDef geoDef = Buildings.def("geothermal_generator");
        for (BlockPos g : geo) {
            BlockPos v = vent(geoDef, g);
            level.setBlockAndUpdate(v.below(3), Blocks.MAGMA_BLOCK.defaultBlockState());
            level.setBlockAndUpdate(v.below(1), Blocks.WATER.defaultBlockState());
            level.setBlockAndUpdate(v.below(2), Block.updateFromNeighbourShapes(Blocks.POTENT_SULFUR.defaultBlockState(), level, v.below(2)));
            ControllerBlockEntity gc = place(source, level, company, "geothermal_generator", g);
            storages(source, level, company, gc, 1);
            geos.add(gc);
        }
        // ---- the Particle Accelerator and the Converters
        ControllerBlockEntity pa = place(source, level, company, "particle_accelerator", ACCELERATOR);
        ControllerBlockEntity conv = place(source, level, company, "converter", CONVERTER);
        ControllerBlockEntity residue = place(source, level, company, "converter", RESIDUE);
        if (pa != null) BuildingLogic.setFilter(level, pa, new ItemStack(Items.DIAMOND));
        if (conv != null) BuildingLogic.setFilter(level, conv, new ItemStack(Items.RAW_IRON));
        if (residue != null) BuildingLogic.setFilter(level, residue, new ItemStack(fluid("siftec:dark_matter_residue").getBucket()));
        augmenters(source, level, company, pa, 2);
        augmenters(source, level, company, conv, 1);
        augmenters(source, level, company, residue, 1);

        // ---- feeding and emptying, every tick, for as long as the test runs
        Port coalWater = port(level, coal, PortBlock.Kind.FLUID_IN, 0), repairWater = port(level, repair, PortBlock.Kind.FLUID_IN, 0),
            nukeWater = port(level, nuke, PortBlock.Kind.FLUID_IN, 0), dieselIn = port(level, diesel, PortBlock.Kind.FLUID_IN, 0),
            turboIn = port(level, turbo, PortBlock.Kind.FLUID_IN, 0), paIn = port(level, pa, PortBlock.Kind.ITEM_IN, 0),
            convA = port(level, conv, PortBlock.Kind.ITEM_IN, 0), convB = port(level, conv, PortBlock.Kind.ITEM_IN, 1),
            resIn = port(level, residue, PortBlock.Kind.ITEM_IN, 0), resOut = port(level, residue, PortBlock.Kind.FLUID_OUT, 0);
        List<Port> acid = new ArrayList<>();
        for (ControllerBlockEntity gc : geos) acid.add(port(level, gc, PortBlock.Kind.FLUID_OUT, 0));
        long[] fed = new long[4]; // diesel droplets, turbofuel droplets, residue droplets out, nothing
        long[] out = new long[3]; // diamonds, raw iron, uranium waste
        int total = WARMUP + MINUTES * MINUTE + 900;
        int[] tick = {0};
        EVERY_TICK.add(() -> {
            tick[0]++;
            if (tick[0] % 10 == 0) {
                pipe(level, coalWater, Fluids.WATER, 2000);
                pipe(level, repairWater, Fluids.WATER, 2000);
                pipe(level, nukeWater, Fluids.WATER, 4000);
                for (Port a : acid) pump(level, a);
            }
            fed[0] += pipe(level, dieselIn, fluid("createdieselgenerators:diesel"), 100);
            fed[1] += pipe(level, turboIn, fluid("siftec:turbofuel"), 100);
            fed[2] += pump(level, resOut);
            feed(level, paIn, Items.COAL, 64);
            feed(level, convA, Items.STONE, 64);
            feed(level, convB, item("siftec:reanimated_sam"), 16);
            feed(level, resIn, item("siftec:reanimated_sam"), 16);
            out[0] += empty(pa, Items.DIAMOND);
            out[1] += empty(conv, Items.RAW_IRON);
            empty(residue, Items.AIR);
            out[2] += empty(nuke, item("siftec:uranium_waste"));
            return tick[0] >= total;
        });
        // the Particle Accelerator's power through its cycles, and the geysers' swing, sampled
        double[] paDemand = {Double.MAX_VALUE, 0};
        double[][] geoSamples = new double[3][3]; // sum, least, most
        int[] geoN = {0};
        for (double[] g : geoSamples) {
            g[1] = Double.MAX_VALUE;
        }
        int[] sampled = {0};
        later(level, WARMUP, () -> EVERY_TICK.add(() -> {
            sampled[0]++;
            if (pa != null && pa.powered && pa.demandSu > BuildingPower.STANDBY_SU) {
                paDemand[0] = Math.min(paDemand[0], pa.demandSu);
                paDemand[1] = Math.max(paDemand[1], pa.demandSu);
            }
            if (sampled[0] % 20 == 0) {
                for (int i = 0; i < geos.size() && i < 3; i++) {
                    double o = offered(level, geos.get(i));
                    geoSamples[i][0] += o;
                    geoSamples[i][1] = Math.min(geoSamples[i][1], o);
                    geoSamples[i][2] = Math.max(geoSamples[i][2], o);
                }
                geoN[0]++;
            }
            return sampled[0] >= MINUTES * MINUTE;
        }));

        // ---- a minute in: what the machines are doing
        later(level, WARMUP - 20, () -> {
            report(source, "warmed up: biomass " + status(level, bio) + " | coal " + status(level, coal) + " | diesel " + status(level, diesel) + " | turbofuel "
                + status(level, turbo) + " | nuclear " + status(level, nuke) + " | accelerator " + status(level, pa) + " | converter " + status(level, conv)
                + " | residue " + status(level, residue));
            machinesRun(source, level, bio, coal, diesel, nuke, pa, conv, geos);
        });
        for (int t = 200; t <= WARMUP + 1200; t += 400) later(level, t, () -> {
            report(source, "inside the accelerator: " + inside(level, pa));
            report(source, "inside the converter: " + inside(level, conv));
            report(source, "inside the residue converter: " + inside(level, residue));
            report(source, "inside the fuel generator: " + status(level, diesel) + ", fuel in its engine " + mb(engineFuel(level, diesel)) + " mB");
        });

        // ---- measuring from the warm-up on
        long[] start = new long[16];
        later(level, WARMUP, () -> {
            start[0] = bio == null ? 0 : bio.made;
            start[1] = coal == null ? 0 : coal.made;
            start[2] = coal == null ? 0 : coal.waterUsed;
            start[3] = fed[0] - engineFuel(level, diesel);
            start[4] = fed[1] - engineFuel(level, turbo);
            start[5] = nuke == null ? 0 : Generators.rodFuel(level, nuke);
            start[6] = nuke == null ? 0 : nuke.waterUsed;
            start[7] = out[0];
            start[8] = out[1];
            start[9] = fed[2];
            start[10] = pa == null ? 0 : pa.launched;
            start[11] = nuke == null ? 0 : nuke.made;
            report(source, "measuring from " + java.util.Arrays.toString(start));
        });
        double[] given = new double[5];
        int[] givenN = {0};
        later(level, WARMUP, () -> EVERY_TICK.add(() -> {
            given[0] += bio == null ? 0 : bio.lastGenerated;
            given[1] += coal == null ? 0 : coal.lastGenerated;
            given[2] += diesel == null ? 0 : diesel.lastGenerated;
            given[3] += turbo == null ? 0 : turbo.lastGenerated;
            given[4] += offered(level, nuke);
            return ++givenN[0] >= MINUTES * MINUTE;
        }));
        later(level, WARMUP + MINUTES * MINUTE, () -> {
            int n = Math.max(1, givenN[0]);
            // Biomass Burner and Coal Generator, full load
            near(source, "Biomass Burner on a full load gives 30 MW (6,144 SU)", given[0] / n, su(30), 0.04);
            near(source, "Biomass Burner burns 4 Solid Biofuel a minute", (bio == null ? 0 : bio.made - start[0]) / (double) MINUTES, 4, 0.15);
            near(source, "Coal Generator on a full load gives 75 MW (15,360 SU)", given[1] / n, su(75), 0.04);
            near(source, "Coal Generator burns 15 Coal a minute", (coal == null ? 0 : coal.made - start[1]) / (double) MINUTES, 15, 0.08);
            near(source, "Coal Generator drinks 45 m3 (1,800 mB) of water a minute", mb(coal == null ? 0 : coal.waterUsed - start[2]) / (double) MINUTES, 1800, 0.05);
            // Fuel Generators
            near(source, "Fuel Generator on Fuel, full load, gives 250 MW (51,200 SU)", given[2] / n, su(250), 0.04);
            near(source, "Fuel Generator burns 20 m3 (800 mB) of Fuel a minute", mb(fed[0] - engineFuel(level, diesel) - start[3]) / (double) MINUTES, 800, 0.05);
            near(source, "Fuel Generator on Turbofuel gives the 100 MW drawn (20,480 SU)", given[3] / n, su(100), 0.04);
            near(source, "and burns Turbofuel for those 100 MW only: 3 m3 (120 mB) a minute", mb(fed[1] - engineFuel(level, turbo) - start[4]) / (double) MINUTES, 120, 0.08);
            // Nuclear Power Plant
            near(source, "Nuclear Power Plant gives 2,500 MW (512,000 SU) on 100 MW of load", given[4] / n, su(2500), 0.03);
            long burnt = start[5] - (nuke == null ? 0 : Generators.rodFuel(level, nuke)) + (nuke == null ? 0 : (nuke.made - start[11]) * 84_000L);
            near(source, "its rods burn 0.2 Uranium Fuel Rods a minute (16,800 fuel units) at full, whatever the load", burnt / (double) MINUTES, 16_800, 0.03);
            near(source, "it drinks 240 m3 (9,600 mB) of water a minute", mb(nuke == null ? 0 : nuke.waterUsed - start[6]) / (double) MINUTES, 9600, 0.05);
            expect(source, "every Uranium Fuel Rod it took left 50 Uranium Waste at its output", nuke != null && nuke.made > 0 && nuke.wasteMade == 50 * nuke.made
                    && out[2] + nuke.output.getItem(0).getCount() == nuke.wasteMade,
                nuke == null ? "missing" : nuke.made + " rods, " + nuke.wasteMade + " waste made, " + out[2] + " taken out");
            // Particle Accelerator and Converters
            near(source, "Particle Accelerator: 30 Diamonds a minute, out of its Crushing Wheels", (out[0] - start[7]) / (double) MINUTES, 30, 0.07);
            // a Diamonds Core carries two crafts (2 s each: content.CORE_SECONDS), so each gives two Diamonds
            expect(source, "every Diamond came from a Core the fan blew up into the wheels, two a Core", pa != null && 2 * (pa.launched - start[10]) >= out[0] - start[7]
                    && 2 * (pa.launched - start[10]) <= out[0] - start[7] + 4 && out[0] > start[7],
                (pa == null ? "?" : pa.launched - start[10]) + " Cores launched, " + (out[0] - start[7]) + " Diamonds");
            near(source, "its least power over a cycle is 250 MW (51,200 SU)", paDemand[0], su(250), 0.07);
            near(source, "its most power over a cycle is 750 MW (153,600 SU)", paDemand[1], su(750), 0.05);
            near(source, "Converter: 120 Iron Ore (Limestone) a minute", (out[1] - start[8]) / (double) MINUTES, 120, 0.1);
            near(source, "Converter: 40 m3 (4,000 mB) of Dark Matter Residue a minute out of its Item Drain", mb(fed[2] - start[9]) / (double) MINUTES, 4000, 0.1);
            expect(source, "the Converter has its one Converter Cell, no more", cells(level, residue) == 1, cells(level, residue) + " cells");
            // Geothermal Generators
            String[] names = {"impure", "normal", "pure"};
            double[][] ranges = {{50, 150}, {100, 300}, {200, 600}};
            for (int i = 0; i < geos.size() && i < 3; i++) {
                double avg = geoSamples[i][0] / Math.max(1, geoN[0]);
                near(source, "Geothermal Generator on a " + names[i] + " geyser averages " + (int) ((ranges[i][0] + ranges[i][1]) / 2) + " MW", avg, su((ranges[i][0] + ranges[i][1]) / 2), 0.06);
                near(source, "and swings down to " + (int) ranges[i][0] + " MW", geoSamples[i][1], su(ranges[i][0]), 0.1);
                near(source, "and up to " + (int) ranges[i][1] + " MW", geoSamples[i][2], su(ranges[i][1]), 0.06);
            }
            stopped(source, level, pa, conv, out);
        });
        repairs(source, level, repair);
    }

    /** The machines inside are the ones running: burners lit, engines and coils turning, wheels and fans. */
    private static void machinesRun(CommandSourceStack source, ServerLevel level, @Nullable ControllerBlockEntity bio, @Nullable ControllerBlockEntity coal,
                                    @Nullable ControllerBlockEntity diesel, @Nullable ControllerBlockEntity nuke, @Nullable ControllerBlockEntity pa,
                                    @Nullable ControllerBlockEntity conv, List<ControllerBlockEntity> geos) {
        for (ControllerBlockEntity c : new ControllerBlockEntity[]{bio, coal}) {
            if (c == null) continue;
            String name = c.def() == null ? "?" : c.def().id();
            boolean lit = true;
            for (BlockPos b : BuildingLogic.cells(c, block("create:blaze_burner"))) {
                lit &= level.getBlockEntity(b) instanceof BlazeBurnerBlockEntity burner && burner.getActiveFuel() != BlazeBurnerBlockEntity.FuelType.NONE;
            }
            expect(source, name + ": its Blaze Burners are lit with the fuel put in them", lit && !BuildingLogic.cells(c, block("create:blaze_burner")).isEmpty(), status(level, c));
            List<FluidTankBlockEntity> boilers = Generators.boilers(level, c);
            int engines = 0;
            for (FluidTankBlockEntity b : boilers) engines += b.boiler.attachedEngines;
            expect(source, name + ": its boiler drives its Steam Engines", boilers.size() == 1 && engines == BuildingLogic.cells(c, block("create:steam_engine")).size(),
                boilers.size() + " boilers, " + engines + " engines");
            float rpm = speed(level, cell(c, "create_new_age:generator_coil"));
            near(source, name + ": the Steam Engines turn its New Age coil at 64 RPM", Math.abs(rpm), 64, 0.01);
        }
        if (diesel != null) {
            near(source, "Fuel Generator: Diesel Generators' engine turns at 256 RPM", Math.abs(speed(level, cell(diesel, "createdieselgenerators:diesel_engine"))), 256, 0.01);
            near(source, "and so does its New Age coil", Math.abs(speed(level, cell(diesel, "create_new_age:generator_coil"))), 256, 0.01);
        }
        if (nuke != null) {
            List<FluidTankBlockEntity> boilers = Generators.boilers(level, nuke);
            StringBuilder b = new StringBuilder();
            boolean ok = boilers.size() == 2;
            for (FluidTankBlockEntity t : boilers) {
                b.append(t.boiler.attachedEngines).append(" engines; ");
                ok &= t.boiler.attachedEngines == 18;
            }
            expect(source, "Nuclear Power Plant: two boilers of 18 Steam Engines each", ok, b.toString());
            long fuel = Generators.rodFuel(level, nuke);
            expect(source, "its New Age reactor rods hold the fuel the plant put in", fuel > 0, fuel + " fuel ticks in its rods");
            near(source, "its coils turn at 256 RPM", Math.abs(speed(level, cell(nuke, "create_new_age:generator_coil"))), 256, 0.01);
        }
        if (pa != null) {
            BlockPos fan = cell(pa, "create:encased_fan"), gap = Processors.gap(pa);
            BlockState fanState = fan == null ? null : level.getBlockState(fan);
            expect(source, "Particle Accelerator: its Encased Fan turns and blows up", fan != null && speed(level, fan) != 0 && fanState.getValue(BlockStateProperties.FACING) == Direction.UP,
                fan == null ? "no fan" : speed(level, fan) + " RPM, " + fanState);
            BlockState crusher = gap == null ? null : level.getBlockState(gap);
            expect(source, "its Crushing Wheels turn against each other and carry upward", crusher != null && crusher.hasProperty(CrushingWheelControllerBlock.VALID)
                    && crusher.getValue(CrushingWheelControllerBlock.VALID) && crusher.getValue(CrushingWheelControllerBlock.FACING) == Direction.UP,
                String.valueOf(crusher));
            expect(source, "it launched Cores into the column", pa.launched > 0, pa.launched + " launched, " + pa.made + " made");
        }
        if (conv != null) {
            BlockPos fan = cell(conv, "create:encased_fan"), fire = cell(conv, "minecraft:soul_campfire");
            BlockState f = fire == null ? null : level.getBlockState(fire);
            expect(source, "Converter: its fan blows through lit soul fire", fan != null && speed(level, fan) != 0 && f != null && f.getValue(BlockStateProperties.LIT),
                (fan == null ? "no fan" : speed(level, fan) + " RPM") + ", " + f);
        }
        for (ControllerBlockEntity g : geos) {
            near(source, "Geothermal Generator: its turbine turns at what its geyser gives (" + status(level, g) + ")",
                Math.abs(speed(level, BuildingLogic.cell(g, com.meakaandre.siftec.registry.ModBlocks.TURBINE.get()))) * Generators.GEO_STRESS, offered(level, g), 0.05);
        }
    }

    /** What is where inside a Particle Accelerator or Converter, for working out a failure. */
    static String inside(ServerLevel level, @Nullable ControllerBlockEntity c) {
        if (c == null) return "missing";
        StringBuilder b = new StringBuilder(status(level, c));
        com.zurrtum.create.content.processing.basin.BasinBlockEntity basin = ProductionLogic.basin(level, c);
        if (basin != null) {
            b.append("; basin");
            for (int i = 0; i < basin.itemCapability.getContainerSize(); i++) {
                ItemStack st = basin.itemCapability.getItem(i);
                if (!st.isEmpty()) b.append(' ').append(st);
            }
            b.append(", filter ").append(basin.getFilter().getFilter());
            Storage<FluidVariant> tanks = ProductionLogic.fluids(level, basin.getBlockPos(), null);
            if (tanks != null) for (StorageView<FluidVariant> v : tanks) if (!v.isResourceBlank() && v.getAmount() > 0) b.append(", ").append(mb(v.getAmount())).append(" mB ").append(BuiltInRegistries.FLUID.getKey(v.getResource().getFluid()));
        }
        BlockPos mixer = cell(c, "create:mechanical_mixer");
        if (mixer != null && level.getBlockEntity(mixer) instanceof com.zurrtum.create.content.kinetics.mixer.MechanicalMixerBlockEntity m) {
            b.append("; mixer ").append(m.getSpeed()).append(" RPM, running ").append(m.running).append(' ').append(m.runningTicks).append('/').append(m.processingTicks)
                .append(", next craft in ").append(c.nextCraft - level.getGameTime());
            com.meakaandre.siftec.company.Company co = com.meakaandre.siftec.owner.Ownership.of(m);
            b.append(", its company ").append(co == null ? "none" : co.id + " (matter_conversion " + co.has("matter_conversion") + ", quantum_encoding " + co.has("quantum_encoding") + ")");
            if (basin != null) b.append(", basin can go on ").append(basin.canContinueProcessing());
        }
        for (int k = 0; k < 3; k++) {
            Port fp = port(level, c, PortBlock.Kind.FLUID_IN, k);
            if (fp != null) b.append("; fluid port ").append(fp.pos().toShortString()).append(" reaches ").append(FluidStorage.SIDED.find(level, fp.pos(), fp.side()) != null);
        }
        BlockPos gap = Processors.gap(c), fan = cell(c, "create:encased_fan");
        if (gap != null && level.getBlockEntity(gap) instanceof com.zurrtum.create.content.kinetics.crusher.CrushingWheelControllerBlockEntity crusher) {
            b.append("; crusher speed ").append(crusher.crushingspeed).append(", holding ").append(crusher.processingEntity)
                .append(", inventory ").append(crusher.inventory.getItem(0)).append(", owner ").append(crusher.getAttached(com.meakaandre.siftec.owner.Ownership.OWNER));
        }
        if (fan != null) {
            for (net.minecraft.world.entity.item.ItemEntity e : level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(fan).inflate(2, 8, 2))) {
                b.append(level.isPositionEntityTicking(e.blockPosition()) ? "; item " : "; (not ticking) item ").append(e.getItem()).append(String.format(Locale.ROOT, " at %.2f %.2f %.2f moving %.3f", e.getX(), e.getY(), e.getZ(), e.getDeltaMovement().y));
            }
        }
        BlockPos depot = cell(c, "create:depot"), deployer = cell(c, "create:deployer");
        if (depot != null && level.getBlockEntity(depot) instanceof com.zurrtum.create.content.logistics.depot.DepotBlockEntity d) {
            var held = d.getHeldItem();
            b.append("; depot ").append(held == null ? "empty" : held.stack + " by " + held.processedBy + " for " + held.processingTime);
        }
        if (deployer != null && level.getBlockEntity(deployer) instanceof com.zurrtum.create.content.kinetics.deployer.DeployerBlockEntity d) {
            b.append("; deployer ").append(d.state).append(' ').append(d.mode).append(" timer ").append(d.timer).append(" holding ").append(d.heldItem)
                .append(" speed ").append(d.getSpeed()).append(" owner ").append(d.getAttached(com.meakaandre.siftec.owner.Ownership.OWNER));
        }
        b.append("; carrier ").append(c.carrier).append(", launched ").append(c.launched).append(", made ").append(c.made);
        return b.toString();
    }

    /** The Converter's cells, wherever they are: carried, on its Depot, in its Deployer or Item Drain (charged or not). */
    private static int cells(ServerLevel level, @Nullable ControllerBlockEntity c) {
        if (c == null) return -1;
        int n = c.carrier.is(Processors.cellItem()) ? c.carrier.getCount() : 0;
        for (String id : new String[]{"create:depot", "create:item_drain"}) {
            BlockPos pos = cell(c, id);
            ItemStack held = pos == null ? ItemStack.EMPTY : ProductionLogic.held(level, new ProductionLogic.Station(pos, id.endsWith("depot") ? "depot" : "drain", null));
            String path = BuiltInRegistries.ITEM.getKey(held.getItem()).getPath();
            if (held.is(Processors.cellItem()) || path.startsWith("loaded_conversion_") || Processors.chargedCell(held)) n++;
        }
        BlockPos deployer = cell(c, "create:deployer");
        if (deployer != null && level.getBlockEntity(deployer) instanceof com.zurrtum.create.content.kinetics.deployer.DeployerBlockEntity d && d.heldItem.is(Processors.cellItem())) n++;
        return n;
    }

    /** With their machines stopped, the Particle Accelerator and the Converter make nothing: the work is the machines'. */
    private static void stopped(CommandSourceStack source, ServerLevel level, @Nullable ControllerBlockEntity pa, @Nullable ControllerBlockEntity conv, long[] out) {
        List<MotorBlockEntity> wheels = new ArrayList<>();
        if (pa != null) for (BlockPos w : BuildingLogic.cells(pa, block("create:crushing_wheel"))) wheels.addAll(motorsBy(level, w));
        List<MotorBlockEntity> fan = conv == null ? List.of() : motorsBy(level, cell(conv, "create:encased_fan"));
        List<int[]> wheelsWas = stop(wheels), fanWas = stop(fan);
        long[] at = new long[2];
        later(level, 100, () -> {
            at[0] = out[0];
            at[1] = out[1];
        });
        later(level, 500, () -> {
            expect(source, "with its Crushing Wheels stopped, the Particle Accelerator makes no Diamonds", wheels.size() == 2 && out[0] - at[0] == 0,
                wheels.size() + " wheel motors stopped, " + (out[0] - at[0]) + " Diamonds in 20 s");
            expect(source, "with its fan stopped, the Converter makes no Iron Ore", fan.size() == 1 && out[1] - at[1] == 0,
                fan.size() + " fan motors stopped, " + (out[1] - at[1]) + " Iron Ore in 20 s");
            restart(wheels, wheelsWas);
            restart(fan, fanWas);
            at[0] = out[0];
            at[1] = out[1];
        });
        later(level, 860, () -> {
            expect(source, "turned again, they make again", out[0] > at[0] && out[1] > at[1], (out[0] - at[0]) + " Diamonds, " + (out[1] - at[1]) + " Iron Ore in 18 s");
            report(source, "result: " + passed + " passed, " + failed + " failed");
        });
    }

    /** A building's machines are kept whole: put back, rebuilt to a new shape, or nothing at all while something is in the way. */
    private static void repairs(CommandSourceStack source, ServerLevel level, @Nullable ControllerBlockEntity c) {
        if (c == null) return;
        List<BlockPos> engines = BuildingLogic.cells(c, block("create:steam_engine"));
        if (engines.isEmpty()) {
            expect(source, "the repair test's Coal Generator has Steam Engines", false, "");
            return;
        }
        BlockPos engine = engines.getFirst(), coil = cell(c, "create_new_age:generator_coil");
        BlockState engineState = level.getBlockState(engine);
        // 1. a Steam Engine taken away is put back within its next look
        later(level, 700, () -> {
            near(source, "repair: the Coal Generator runs before (15,360 SU)", offered(level, c), su(75), 0.04);
            level.setBlock(engine, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        });
        later(level, 700 + BuildingRepair.EVERY + 40, () -> expect(source, "repair: a Steam Engine taken away is put back from the building's shape",
            level.getBlockState(engine).is(engineState.getBlock()), String.valueOf(level.getBlockState(engine))));
        later(level, 700 + BuildingRepair.EVERY + 400, () -> near(source, "repair: and the generator gives its 75 MW again (" + status(level, c) + ")", offered(level, c), su(75), 0.04));
        // 2. a building from before its shape changed (shape 0): rebuilt whole, its missing coil too
        later(level, 1400, () -> {
            if (coil != null) level.setBlock(coil, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            c.shape = 0;
            BuildingRepair.Result r = BuildingRepair.check(level, c);
            BuildingTemplate t = BuildingTemplate.of(c.def());
            expect(source, "repair: a building of an older shape is rebuilt to the current one", r == BuildingRepair.Result.REBUILT && t != null && c.shape == BuildingRepair.hash(t)
                && coil != null && level.getBlockState(coil).is(block("create_new_age:generator_coil")), r + ", shape " + c.shape);
        });
        later(level, 1900, () -> near(source, "repair: and it runs on its rebuilt machines (15,360 SU)", offered(level, c), su(75), 0.04));
        // 3. something not the building's where a Steam Engine goes: nothing is rebuilt there and it makes nothing until it is cleared
        later(level, 2000, () -> {
            BuildingIndex index = BuildingIndex.get(level);
            if (index != null) index.remove(level, engine, c.getBlockPos());
            level.setBlock(engine, Blocks.COBBLESTONE.defaultBlockState(), Block.UPDATE_ALL);
            BuildingRepair.Result r = BuildingRepair.check(level, c);
            expect(source, "repair: a block in the way of a Steam Engine leaves the building broken", r == BuildingRepair.Result.BLOCKED && c.broken
                && level.getBlockState(engine).is(Blocks.COBBLESTONE), r + ", " + status(level, c));
        });
        later(level, 2100, () -> expect(source, "repair: and a broken generator gives nothing (no power made in code)", c.offered == 0 && c.lastGenerated == 0,
            c.offered + " SU offered, " + c.lastGenerated + " given; " + status(level, c)));
        later(level, 2120, () -> {
            level.setBlock(engine, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            BuildingRepair.Result r = BuildingRepair.check(level, c);
            expect(source, "repair: once cleared, the Steam Engine is put back and the building runs", r == BuildingRepair.Result.REBUILT && !c.broken
                && level.getBlockState(engine).is(engineState.getBlock()), r + ", " + status(level, c));
        });
        later(level, 2600, () -> near(source, "repair: it gives its 75 MW again (" + status(level, c) + ")", offered(level, c), su(75), 0.04));
    }

    // ------------------------------------------------------------------------------------------------ after a restart

    private static void afterRestart(CommandSourceStack source, ServerLevel level) {
        // the state selftest clears the test companies after a restart: the machines run their company's recipes only
        LATER.add(new Later(level.getGameTime() + 100, () -> company(level)));
        ControllerBlockEntity bio = controller(level, "biomass_burner", BIOMASS), coal = controller(level, "coal_generator", COAL),
            diesel = controller(level, "fuel_generator", DIESEL), nuke = controller(level, "nuclear_power_plant", NUCLEAR),
            pa = controller(level, "particle_accelerator", ACCELERATOR), conv = controller(level, "converter", CONVERTER);
        List<ControllerBlockEntity> all = new ArrayList<>(java.util.Arrays.asList(bio, coal, diesel, nuke, pa, conv));
        expect(source, "after restart: the buildings are there", !all.contains(null), all.stream().map(c -> c == null ? "missing" : c.buildingId).toList().toString());
        if (all.contains(null)) {
            report(source, "result: " + passed + " passed, " + failed + " failed");
            return;
        }
        bio.input.setItem(0, new ItemStack(item("siftec:solid_biofuel"), 64));
        coal.input.setItem(0, new ItemStack(Items.COAL, 64));
        // the reactor burns at full whatever the load: the smoke run's other tests (ten in-game minutes of always-running
        // factories) may have burnt what it had and filled its waste output; it is given its rods again and emptied
        nuke.input.setItem(0, new ItemStack(item("siftec:uranium_fuel_rod"), 12));
        empty(nuke, item("siftec:uranium_waste"));
        Port coalWater = port(level, coal, PortBlock.Kind.FLUID_IN, 0), nukeWater = port(level, nuke, PortBlock.Kind.FLUID_IN, 0),
            dieselIn = port(level, diesel, PortBlock.Kind.FLUID_IN, 0), paIn = port(level, pa, PortBlock.Kind.ITEM_IN, 0),
            convA = port(level, conv, PortBlock.Kind.ITEM_IN, 0), convB = port(level, conv, PortBlock.Kind.ITEM_IN, 1);
        long[] out = new long[2];
        long madeBio = bio.made, madeCoal = coal.made;
        int[] tick = {0};
        EVERY_TICK.add(() -> {
            if (++tick[0] % 10 == 0) {
                pipe(level, coalWater, Fluids.WATER, 2000);
                pipe(level, nukeWater, Fluids.WATER, 4000);
            }
            pipe(level, dieselIn, fluid("createdieselgenerators:diesel"), 100);
            feed(level, paIn, Items.COAL, 64);
            feed(level, convA, Items.STONE, 64);
            feed(level, convB, item("siftec:reanimated_sam"), 16);
            out[0] += empty(pa, Items.DIAMOND);
            out[1] += empty(conv, Items.RAW_IRON);
            return tick[0] >= 700;
        });
        report(source, "after restart: refuelled; checks in 30 s");
        later(level, 600, () -> {
            for (ControllerBlockEntity c : all) {
                BuildingTemplate t = BuildingTemplate.of(c.def());
                expect(source, "after restart: " + c.buildingId + " kept its shape and is whole", t != null && c.shape == BuildingRepair.hash(t) && !c.broken, c.shape + ", " + status(level, c));
            }
            near(source, "after restart: the Biomass Burner gives 30 MW on its machines", offered(level, bio), su(30), 0.04);
            near(source, "after restart: the Coal Generator gives 75 MW on its machines", offered(level, coal), su(75), 0.04);
            near(source, "after restart: the Fuel Generator gives 250 MW on its engine", offered(level, diesel), su(250), 0.04);
            near(source, "after restart: the Nuclear Power Plant gives 2,500 MW on its reactor", offered(level, nuke), su(2500), 0.04);
            report(source, "after restart: the Nuclear Power Plant's reactor: " + reactorState(level, nuke));
            expect(source, "after restart: the burners burn again", bio.made > madeBio || coal.made > madeCoal, (bio.made - madeBio) + " biofuel, " + (coal.made - madeCoal) + " coal");
            expect(source, "after restart: the Particle Accelerator and the Converter make again", out[0] > 0 && out[1] > 0, out[0] + " Diamonds, " + out[1] + " Iron Ore");
            report(source, "result: " + passed + " passed, " + failed + " failed");
        });
    }
}
