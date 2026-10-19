package com.meakaandre.siftec.building;

import com.meakaandre.siftec.geyser.GeyserPurity;
import com.meakaandre.siftec.geyser.Vents;
import com.meakaandre.siftec.mixin.building.BlazeBurnerAccessor;
import com.meakaandre.siftec.node.Purity;
import com.meakaandre.siftec.power.Power;
import com.meakaandre.siftec.registry.ModBlocks;
import com.zurrtum.create.content.fluids.tank.FluidTankBlockEntity;
import com.zurrtum.create.content.processing.burner.BlazeBurnerBlockEntity;
import com.zurrtum.create.infrastructure.fluids.FluidStack;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluids;
import org.antarcticgardens.cna.content.electricity.generation.brushes.CarbonBrushesBlockEntity;
import org.antarcticgardens.cna.content.nuclear.reactor.fuelacceptor.ReactorFuelAcceptorBlockEntity;
import org.antarcticgardens.cna.content.nuclear.reactor.rod.ReactorRodBlockEntity;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The machines inside the generator buildings, which make the power ({@link BuildingPower} only hands it to the grid):
 * <ul>
 * <li>Biomass Burner and Coal Generator: a Create boiler (a Fluid Tank over Blaze Burners) drives Steam Engines, which turn
 * a Create: New Age generator coil; the building puts the next fuel into a burner as it burns low, at Satisfactory's
 * megajoules, and the Coal Generator's boiler drinks from its intake tank;</li>
 * <li>Fuel Generator: Diesel Generators' engine, fed at the fuel port, turns the coil at the fuel's Satisfactory numbers;</li>
 * <li>Nuclear Power Plant: New Age's reactor (fuel acceptors, rods, heat vents) heats New Age heaters under two boilers whose
 * Steam Engines turn the coils; the building puts a fuel rod into an acceptor as its rods run low;</li>
 * <li>Geothermal Generator: the geyser drives its turbine at the speed its purity and Satisfactory's swing give.</li>
 * </ul>
 * The coils' energy collects in their carbon brushes; what the brushes hold is all the building can give, and what the
 * grid takes comes out of them. The grid's share of that (the load) sets how fast the fuel burns, as in Satisfactory
 * (the Nuclear Power Plant burns at full whatever the load, as 1.0's does).
 */
public final class Generators {
    /** The Fuel Generator's engine: its speed, and the SU it gives (its coil's 51,200 SU and the coil's own drag, 6,144). */
    public static final float FUEL_SPEED = 256, FUEL_STRENGTH = 60_000;
    /** SU a turn the Geothermal Generator's coils take off its turbine: its twenty netherite magnets. */
    public static final double GEO_STRESS = 480;
    /** Satisfactory 1.0's geyser: its MW from least to most at each purity, and how long one swing takes. */
    public static final double GEO_SWING_TICKS = 1200;
    /** Burn ticks a Blaze Burner has left when the building puts its next fuel in. */
    static final int BURNER_REFILL = 20;
    /** Fuel ticks a reactor rod has left when the building puts the next rod into its acceptor. */
    static final int ROD_REFILL = 200;
    /** The least load a boiler's water need follows, so a level always takes some water. */
    static final float MIN_WATER_LOAD = 0.05f;
    /** Ticks over which a boiler's water use follows its load (the boiler measures its water over 50). */
    static final int WATER_WINDOW = 64;

    private Generators() {
    }

    static Block block(String id) {
        return BuiltInRegistries.BLOCK.getValue(Identifier.parse(id));
    }

    // ------------------------------------------------------------------------------------------------ the brushes

    /** SU the building's coils made this tick, as its carbon brushes hold it: what it can give the grid now. */
    public static double offered(ServerLevel level, ControllerBlockEntity c) {
        long fe = 0;
        for (BlockPos pos : BuildingLogic.cells(c, block("create_new_age:carbon_brushes"))) {
            if (level.getBlockEntity(pos) instanceof CarbonBrushesBlockEntity brushes) {
                fe += Math.min(brushes.getEnergyStorage().getStoredEnergy(), brushes.getLastOutput());
            }
        }
        return Power.suOfFe(fe);
    }

    /** Takes what the grid drew out of the brushes. */
    static void take(ServerLevel level, ControllerBlockEntity c, double su) {
        long fe = Math.round(Power.feOfSu(su));
        for (BlockPos pos : BuildingLogic.cells(c, block("create_new_age:carbon_brushes"))) {
            if (fe <= 0) return;
            if (level.getBlockEntity(pos) instanceof CarbonBrushesBlockEntity brushes) fe -= brushes.getEnergyStorage().internalExtract(fe, false);
        }
    }

    /** The share of its power the grid drew on the last tick: 0 if it drew none, or the building is on no grid. */
    static float load(ServerLevel level, ControllerBlockEntity c) {
        return c.tripped || c.loadAt < level.getGameTime() - 1 ? 0 : c.load;
    }

    /** SU its coils turn the shaft's power into, read off the coils (for status lines and the selftests). */
    public static double coilSu(ServerLevel level, ControllerBlockEntity c) {
        double su = 0;
        for (BlockPos pos : BuildingLogic.cells(c, block("create_new_age:generator_coil"))) {
            if (level.getBlockEntity(pos) instanceof org.antarcticgardens.cna.content.electricity.generation.coil.GeneratorCoilBlockEntity coil) {
                su += Math.max(0, coil.calculateStressApplied() - 24) * Math.abs(coil.getSpeed());
            }
        }
        return su;
    }

    // ------------------------------------------------------------------------------------------------ every tick

    static void tick(ServerLevel level, ControllerBlockEntity c, BuildingDef def) {
        switch (def.power()) {
            case "biomass", "coal" -> steam(level, c, def);
            case "fuel" -> diesel(level, c, def);
            case "nuclear" -> nuclear(level, c, def);
            case "geothermal" -> geothermal(level, c, def);
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ boilers

    private static void steam(ServerLevel level, ControllerBlockEntity c, BuildingDef def) {
        float load = load(level, c);
        List<BlockPos> burners = BuildingLogic.cells(c, block("create:blaze_burner"));
        double perBurner = def.mw() / Math.max(1, burners.size());
        boolean lit = false, fuelled = !c.input.getItem(0).isEmpty();
        for (BlockPos pos : burners) {
            if (!(level.getBlockEntity(pos) instanceof BlazeBurnerBlockEntity burner)) continue;
            ((BurnerLoad) burner).siftec$load(load);
            boolean out = burner.getActiveFuel() == BlazeBurnerBlockEntity.FuelType.NONE;
            if (out || burner.getRemainingBurnTime() <= BURNER_REFILL) {
                ItemStack in = c.input.getItem(0);
                double mj = BuildingPower.itemMj(def.power(), in);
                if (mj > 0) {
                    // the next fuel goes into the burner: its Satisfactory megajoules, at this burner's share of the MW
                    int ticks = (int) Math.max(1, Math.round(mj / perBurner * 20));
                    in.shrink(1);
                    c.input.setChanged();
                    BlazeBurnerAccessor fuel = (BlazeBurnerAccessor) burner;
                    fuel.siftec$setActiveFuel(BlazeBurnerBlockEntity.FuelType.NORMAL);
                    fuel.siftec$setRemainingBurnTime((out ? 0 : burner.getRemainingBurnTime()) + ticks);
                    burner.updateBlockState();
                    c.made++;
                    c.setChanged();
                }
            }
            lit |= burner.getActiveFuel() != BlazeBurnerBlockEntity.FuelType.NONE;
        }
        boolean water = water(level, c, def, load, false);
        if (!lit) c.machineProblem = fuelled ? "siftec.power.wrong_fuel" : "siftec.power.no_fuel";
        else if (!water) c.machineProblem = "siftec.power.no_water";
        else c.machineProblem = "";
    }

    /** The building's water intake: the tank its fluid input port fills (null if it has none). */
    static @Nullable BlockPos intake(ControllerBlockEntity c) {
        Placement p = c.placement();
        BuildingTemplate t = p == null ? null : BuildingTemplate.of(p.def());
        if (t == null) return null;
        for (BuildingTemplate.Cell cell : t.members) {
            if (cell.state().is(ModBlocks.BUILDING_PORT.get()) && cell.state().getValue(PortBlock.KIND) == PortBlock.Kind.FLUID_IN && cell.target() != null) {
                return p.at(cell.target()[0], cell.target()[1], cell.target()[2]);
            }
        }
        return null;
    }

    /** The building's boilers: the Fluid Tanks with Steam Engines on them (each multiblock once, by its controller). */
    static List<FluidTankBlockEntity> boilers(ServerLevel level, ControllerBlockEntity c) {
        Set<FluidTankBlockEntity> out = new LinkedHashSet<>();
        for (BlockPos pos : BuildingLogic.cells(c, block("create:fluid_tank"))) {
            if (!(level.getBlockEntity(pos) instanceof FluidTankBlockEntity tank)) continue;
            FluidTankBlockEntity controller = tank.getControllerBE();
            if (controller != null && controller.boiler.attachedEngines > 0) out.add(controller);
        }
        return new ArrayList<>(out);
    }

    /**
     * Waters the boilers from the intake tank: Satisfactory's water a tick at this load (the building's {@code water}, m3 a
     * minute at full), shared out by their engines, and each boiler told how much one of its heat levels takes, so a boiler
     * that gets all its water runs every level its burners heat. A building with no water use (the Biomass Burner) has a
     * closed boiler that needs none. False if the boilers are dry.
     */
    static boolean water(ServerLevel level, ControllerBlockEntity c, BuildingDef def, float load, boolean constant) {
        List<FluidTankBlockEntity> boilers = boilers(level, c);
        if (def.water() <= 0) {
            for (FluidTankBlockEntity b : boilers) ((BoilerWater) b.boiler).siftec$waterPerLevel(0);
            return true;
        }
        int engines = 0;
        for (FluidTankBlockEntity b : boilers) engines += b.boiler.attachedEngines;
        if (engines == 0) return false;
        // droplets a tick at full load
        double full = def.water() * BuildingPower.mbPerM3() / 1200.0 * (FluidConstants.BUCKET / 1000.0);
        float use = constant ? 1 : load;
        // the boiler measures what it was given over the last 50 ticks: a level's need follows the least load of a while
        // longer, so a rising load never finds it short of what it has just been given
        c.loadWindow[(int) (level.getGameTime() % WATER_WINDOW)] = use;
        float least = 1;
        for (float f : c.loadWindow) least = Math.min(least, f);
        // a generator that is not giving power yet (just built or just loaded) starts with every level free, as its boilers
        // take water only once they heat; once it runs, a moment of no load in the window never lets the boiler run every
        // level on a trickle: its need follows at least the floor
        boolean startingUp = c.offered <= 0 && c.lastGenerated <= 0;
        least = startingUp ? 0 : Math.max(least, MIN_WATER_LOAD);
        BlockPos intake = intake(c);
        boolean wet = intake != null && ProductionLogic.mbAt(level, intake, Fluids.WATER) > 0;
        for (FluidTankBlockEntity b : boilers) {
            float perLevel = !wet ? Float.POSITIVE_INFINITY : least <= 0 ? 0 : (float) (full / engines * least);
            ((BoilerWater) b.boiler).siftec$waterPerLevel(perLevel);
        }
        if (!wet) return false;
        // water comes at least at the floor's rate too, so a boiler with no load yet can start its first level
        c.waterOwed += full * Math.max(use, MIN_WATER_LOAD);
        long droplets = (long) c.waterOwed;
        if (droplets <= 0) return true;
        Storage<FluidVariant> from = FluidStorage.SIDED.find(level, intake, null);
        if (from == null) return false;
        long moved = 0;
        try (Transaction tx = Transaction.openOuter()) {
            long took = from.extract(FluidVariant.of(Fluids.WATER), droplets, tx);
            for (FluidTankBlockEntity b : boilers) {
                long share = took * b.boiler.attachedEngines / engines;
                Storage<FluidVariant> into = FluidStorage.SIDED.find(level, b.getBlockPos(), null);
                if (into != null) moved += into.insert(FluidVariant.of(Fluids.WATER), share, tx);
            }
            // what the boilers did not take stays in the intake
            if (moved < took) from.insert(FluidVariant.of(Fluids.WATER), took - moved, tx);
            tx.commit();
        }
        // only what really went into the boilers is paid off: a shortfall stays owed (up to a second's worth)
        c.waterOwed -= moved;
        if (c.waterOwed > full * 20) c.waterOwed = full * 20;
        c.waterUsed += moved;
        // boilers with water in them are not dry, even when full enough to take none this tick
        return moved > 0 || boilersHold(level, boilers);
    }

    /** True if any of the boilers holds water. */
    private static boolean boilersHold(ServerLevel level, List<FluidTankBlockEntity> boilers) {
        for (FluidTankBlockEntity b : boilers) {
            Storage<FluidVariant> tank = FluidStorage.SIDED.find(level, b.getBlockPos(), null);
            if (tank == null) continue;
            for (var view : tank) if (!view.isResourceBlank() && view.getResource().getFluid() == Fluids.WATER && view.getAmount() > 0) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------------ Fuel Generator

    private static void diesel(ServerLevel level, ControllerBlockEntity c, BuildingDef def) {
        BlockPos at = BuildingLogic.cell(c, block("createdieselgenerators:diesel_engine"));
        if (at == null || !(level.getBlockEntity(at) instanceof EngineControl engine)) {
            c.machineProblem = "siftec.power.no_engine";
            return;
        }
        FluidStack fuel = engine.siftec$fuel();
        double mjPerMb = fuel.isEmpty() ? 0 : BuildingPower.fluidMj("fuel", fuel.getFluid());
        if (mjPerMb > 0) {
            // Satisfactory's MW out of this fuel's megajoules: mB a tick at full load
            engine.siftec$set(FUEL_SPEED, FUEL_STRENGTH, (float) (def.mw() / 20 / mjPerMb), load(level, c));
            c.machineProblem = "";
        } else {
            // not a fuel the Fuel Generator burns (or none): the engine gives nothing and burns nothing
            engine.siftec$set(0, 0, 0, 0);
            c.machineProblem = fuel.isEmpty() ? "siftec.power.no_fuel" : "siftec.power.wrong_fuel";
        }
    }

    // ------------------------------------------------------------------------------------------------ Nuclear Power Plant

    /** The reactor rods a fuel acceptor fills (New Age's lines out from it). */
    static List<ReactorRodBlockEntity> rods(ReactorFuelAcceptorBlockEntity acceptor) {
        List<ReactorRodBlockEntity> out = new ArrayList<>();
        for (Direction d : Direction.values()) acceptor.findRods(out, d);
        return out;
    }

    private static void nuclear(ServerLevel level, ControllerBlockEntity c, BuildingDef def) {
        boolean fed = false, waiting = false;
        // its water first: with none (or its fuse blown) no rod goes in, so it never burns rods dry
        boolean water = water(level, c, def, 1, true);
        List<ReactorFuelAcceptorBlockEntity> acceptors = new ArrayList<>();
        for (BlockPos pos : BuildingLogic.cells(c, block("create_new_age:reactor_fuel_acceptor"))) {
            if (level.getBlockEntity(pos) instanceof ReactorFuelAcceptorBlockEntity acceptor) acceptors.add(acceptor);
        }
        // the waste still to come from rods its acceptors hold, so they cannot all take a rod at once past a full stack
        int promised = 0;
        for (ReactorFuelAcceptorBlockEntity acceptor : acceptors) promised += BuildingPower.pendingWaste(acceptor.container.getItem(0));
        for (ReactorFuelAcceptorBlockEntity acceptor : acceptors) {
            List<ReactorRodBlockEntity> rods = rods(acceptor);
            if (rods.isEmpty()) continue;
            int least = Integer.MAX_VALUE;
            for (ReactorRodBlockEntity rod : rods) least = Math.min(least, rod.fuel);
            if (least > 0) fed = true;
            if (least > ROD_REFILL || !acceptor.container.getItem(0).isEmpty()) continue;
            if (!water || c.tripped) continue;
            ItemStack in = c.input.getItem(0);
            if (BuildingPower.itemMj("nuclear", in) <= 0) {
                waiting = true;
                continue;
            }
            if (!BuildingPower.wasteFits(c, in, promised)) {
                c.machineProblem = "siftec.power.waste_full";
                continue;
            }
            promised += BuildingPower.pendingWaste(in.copyWithCount(1));
            // the next rod into this acceptor; New Age shares its fuel out over the acceptor's rods, and the waste it
            // leaves comes to the plant's output (BuildingPower.reactorWaste)
            acceptor.container.setItem(0, in.split(1));
            acceptor.setChanged();
            c.input.setChanged();
            c.made++;
            c.setChanged();
        }
        if (!water) c.machineProblem = "siftec.power.no_water";
        else if (!fed && waiting) c.machineProblem = "siftec.power.no_fuel";
        else if (!c.machineProblem.equals("siftec.power.waste_full")) c.machineProblem = "";
    }

    /** Fuel ticks left in all the plant's rods. */
    static long rodFuel(ServerLevel level, ControllerBlockEntity c) {
        long n = 0;
        for (BlockPos pos : BuildingLogic.cells(c, block("create_new_age:reactor_rod"))) {
            if (level.isLoaded(pos) && level.getBlockEntity(pos) instanceof ReactorRodBlockEntity rod) n += rod.fuel;
        }
        return n;
    }

    // ------------------------------------------------------------------------------------------------ Geothermal

    /** The Geothermal Generator's MW at this moment: its geyser's purity's range, swinging over a minute. */
    public static double geyserMw(Purity purity, long time, BlockPos vent) {
        double[] range = switch (purity) {
            case IMPURE -> new double[]{50, 150};
            case PURE -> new double[]{200, 600};
            default -> new double[]{100, 300};
        };
        double mid = (range[0] + range[1]) / 2, half = (range[1] - range[0]) / 2;
        // each geyser at its own point in the swing
        double phase = (com.meakaandre.siftec.node.NodeMap.mix(vent.asLong()) >>> 40) / (double) (1L << 24);
        return mid + half * Math.sin(2 * Math.PI * (time / GEO_SWING_TICKS + phase));
    }

    private static void geothermal(ServerLevel level, ControllerBlockEntity c, BuildingDef def) {
        BlockPos turbinePos = BuildingLogic.cell(c, ModBlocks.TURBINE.get());
        TurbineBlockEntity turbine = turbinePos != null && level.getBlockEntity(turbinePos) instanceof TurbineBlockEntity t ? t : null;
        BlockPos vent = BuildingPower.vent(level, c);
        double mw = 0;
        if (vent == null || !Vents.gasEscapes(level, vent)) {
            c.machineProblem = "siftec.power.no_geyser";
        } else if (!BuildingPower.acidRoom(level, c)) {
            c.machineProblem = "siftec.power.acid_full";
        } else {
            c.machineProblem = "";
            mw = geyserMw(GeyserPurity.of(level, vent), level.getGameTime(), vent);
        }
        if (turbine == null) return;
        float rpm = (float) Math.min(256, Power.su(mw) / GEO_STRESS);
        // a new speed once a second (the kinetic network is worked out again for every change)
        if (level.getGameTime() % 20 == 0 || rpm == 0 && turbine.speed != 0) turbine.drive(rpm);
    }
}
