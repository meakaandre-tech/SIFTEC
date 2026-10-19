package com.meakaandre.siftec.building;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.meakaandre.siftec.fluid.FluidEntry;
import com.meakaandre.siftec.fluid.ModFluids;
import com.meakaandre.siftec.geyser.Vents;
import com.meakaandre.siftec.hub.Milestones;
import com.meakaandre.siftec.power.GridStats;
import com.meakaandre.siftec.power.Power;
import com.meakaandre.siftec.power.PowerGrids;
import com.meakaandre.siftec.power.PowerMember;
import com.meakaandre.siftec.registry.ModBlocks;
import com.zurrtum.create.content.kinetics.base.KineticBlockEntity;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.PotentSulfurBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.PotentSulfurState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import org.antarcticgardens.cna.content.motor.MotorBlockEntity;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * How a Build Gun building takes part in its power grid ({@link PowerGrids}), by its definition's {@code power}:
 * <ul>
 * <li>a powered building (any with SU and no power role of its own) is a consumer: on a grid with power, its New Age
 * motors are given exactly the FE for its SU every tick, so they turn its machines; with none, they stop. Like
 * Satisfactory's machines it draws only while it works: idle (nothing to work on, its output full) it asks only
 * {@link #STANDBY_SU} and its motors wind down until there is work again ({@link BuildingLogic#working});</li>
 * <li>"biomass", "coal", "fuel", "nuclear": generators that burn what their input port brings (and the water their fluid
 * port brings) at Satisfactory's energy, for what the grid draws from them only, and give Satisfactory's MW;</li>
 * <li>"geothermal": the Geothermal Generator over a sulfur geyser, giving a burst at each eruption and leaving sulfuric
 * acid in its tank;</li>
 * <li>"augmenter": the Alien Power Augmenter, a flat 500 MW and +10% (+30% while fed Alien Power Matrices) to its grid's
 * generators;</li>
 * <li>"storage": Power Storage, 100 MWh charged at up to 100 MW;</li>
 * <li>"pole": Power Poles and Towers, nothing but connectors.</li>
 * </ul>
 * Generators and storage hold the grid's fuse; using one while sneaking resets it.
 */
public final class BuildingPower {
    /** Sulfuric acid a second of running leaves in the Geothermal Generator's tank (the old Geyser Engine's rate). */
    public static final int ACID_PER_SECOND = 50;
    /** Ticks an Alien Power Matrix keeps an Augmenter fed (Satisfactory feeds it five a minute). */
    public static final int MATRIX_TICKS = 240;
    /** What an idle powered building draws: next to nothing (Satisfactory's idle machines draw none). */
    public static final double STANDBY_SU = 1;
    /** Satisfactory's Power Storage: 100 MWh, charged at up to 100 MW. */
    public static final double STORAGE_MWH = 100, STORAGE_CHARGE_MW = 100;

    private BuildingPower() {
    }

    /** The building's part in its grid, or null if it has none (a pole, storage containers, the Portable Miner). */
    public static @Nullable PowerMember member(ServerLevel level, ControllerBlockEntity c) {
        BuildingDef def = c.def();
        if (def == null) return null;
        if (c.member != null && c.memberFor == def) return c.member;
        PowerMember m = switch (def.power()) {
            case "biomass", "coal", "fuel", "nuclear", "geothermal" -> new Generator(level, c, def);
            case "augmenter" -> new Augmenter(level, c, def);
            case "storage" -> new Store(c);
            case "pole" -> null;
            default -> def.su() > 0 ? new Consumer(level, c, def) : null;
        };
        c.member = m;
        c.memberFor = def;
        return m;
    }

    // ------------------------------------------------------------------------------------------------ consumers

    /** A powered building: its New Age motors run on the grid. */
    static final class Consumer implements PowerMember {
        final ServerLevel level;
        final ControllerBlockEntity c;
        final BuildingDef def;

        Consumer(ServerLevel level, ControllerBlockEntity c, BuildingDef def) {
            this.level = level;
            this.c = c;
            this.def = def;
        }

        /**
         * Its SU while it works, and only {@link #STANDBY_SU} while it is idle (nothing to work on, or its output full),
         * as Satisfactory's machines draw power only while they run. The standby draw keeps it on its grid, so it still
         * knows whether it has power (a blown fuse stops it), at next to no cost.
         */
        @Override
        public double demand() {
            if (c.isRemoved() || c.broken) return 0;
            if (BuildingLogic.idle(level, c, def)) {
                c.demandSu = STANDBY_SU;
                return STANDBY_SU;
            }
            // the Particle Accelerator and the Converter draw Satisfactory's power for where they are in their recipe's cycle
            c.demandSu = def.logic().equals("processor") ? Processors.demand(level, c, def) : def.su();
            return c.demandSu;
        }

        @Override
        public boolean gone() {
            return c.isRemoved();
        }

        @Override
        public void supplied(boolean on) {
            c.powered = on;
            // idle, its motors get nothing and wind down until there is work again
            if (!on || c.idle) return;
            List<BlockPos> motors = motors(c);
            if (motors.isEmpty()) return;
            // each motor gets its share of the building's SU as FE; a New Age motor fed less than its full need gives
            // SU in proportion, so it gives exactly this share
            long fe = Math.round(Power.feOfSu(c.demandSu / (double) motors.size()));
            for (BlockPos pos : motors) {
                if (level.getBlockEntity(pos) instanceof MotorBlockEntity motor) {
                    var storage = motor.getEnergyStorage();
                    storage.internalInsert(Math.min(fe, storage.getCapacity() - storage.getStoredEnergy()), false);
                }
            }
        }

        @Override
        public void grid(GridStats stats) {
            c.grid = stats;
        }
    }

    /** The New Age motors of a building. */
    public static List<BlockPos> motors(ControllerBlockEntity c) {
        List<BlockPos> out = new ArrayList<>();
        Placement p = c.placement();
        BuildingTemplate t = p == null ? null : BuildingTemplate.of(p.def());
        if (t == null) return out;
        for (BuildingTemplate.Cell cell : t.members) if (cell.state().getBlock() instanceof org.antarcticgardens.cna.content.motor.MotorBlock) out.add(p.at(cell));
        return out;
    }

    // ------------------------------------------------------------------------------------------------ fuse holders

    private abstract static class FuseHolder implements PowerMember {
        final ControllerBlockEntity c;

        FuseHolder(ControllerBlockEntity c) {
            this.c = c;
        }

        @Override
        public boolean holdsFuse() {
            return true;
        }

        @Override
        public boolean gone() {
            return c.isRemoved();
        }

        @Override
        public boolean tripped() {
            return c.tripped;
        }

        @Override
        public void trip(boolean tripped) {
            if (c.tripped != tripped) {
                c.tripped = tripped;
                c.setChanged();
            }
        }

        @Override
        public void grid(GridStats stats) {
            c.grid = stats;
        }
    }

    // ------------------------------------------------------------------------------------------------ generators

    /** What a generator burns: item (or tag, "#...") or fluid id, to megajoules (a fluid's per cubic metre). */
    private static final Map<String, Map<String, Double>> FUEL = new HashMap<>();
    /** Nuclear rods: the waste each leaves, and how many. */
    private static final Map<String, String> WASTE = new HashMap<>();
    private static final Map<String, Integer> WASTE_COUNT = new HashMap<>();
    private static int mbPerM3 = 40;
    private static boolean read;

    public static void forget() {
        read = false;
        FUEL.clear();
        WASTE.clear();
        WASTE_COUNT.clear();
    }

    private static synchronized void readFuel() {
        if (read) return;
        read = true;
        JsonObject raw = Milestones.raw();
        if (raw == null || !raw.has("generator_fuel")) return;
        JsonObject all = raw.getAsJsonObject("generator_fuel");
        if (raw.has("mb_per_m3")) mbPerM3 = raw.get("mb_per_m3").getAsInt();
        for (String kind : all.keySet()) {
            Map<String, Double> table = new HashMap<>();
            JsonObject o = all.getAsJsonObject(kind);
            for (String key : o.keySet()) {
                JsonElement v = o.get(key);
                if (v.isJsonObject()) {
                    JsonObject rod = v.getAsJsonObject();
                    table.put(key, rod.get("mj").getAsDouble());
                    if (rod.has("waste")) {
                        WASTE.put(key, rod.get("waste").getAsString());
                        WASTE_COUNT.put(key, rod.get("count").getAsInt());
                    }
                } else {
                    table.put(key, v.getAsDouble());
                }
            }
            FUEL.put(kind, table);
        }
    }

    /** The fluids a generator of this kind burns ("fuel": the Fuel Generator's), by id. */
    public static java.util.Set<String> fluidFuels(String kind) {
        readFuel();
        return java.util.Set.copyOf(FUEL.getOrDefault(kind, Map.of()).keySet());
    }

    /** Megajoules of one item of this fuel in a generator of this kind, or 0 if it does not burn it. */
    public static double itemMj(String kind, ItemStack stack) {
        readFuel();
        if (stack.isEmpty()) return 0;
        Map<String, Double> table = FUEL.getOrDefault(kind, Map.of());
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        Double mj = table.get(id);
        if (mj != null) return mj;
        for (Map.Entry<String, Double> e : table.entrySet()) {
            if (e.getKey().startsWith("#") && stack.is(net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM, Identifier.parse(e.getKey().substring(1))))) {
                return e.getValue();
            }
        }
        return 0;
    }

    /** Megajoules of one mB of this fluid in a generator of this kind, or 0. */
    public static double fluidMj(String kind, Fluid fluid) {
        readFuel();
        Map<String, Double> table = FUEL.getOrDefault(kind, Map.of());
        Fluid still = fluid instanceof net.minecraft.world.level.material.FlowingFluid flowing ? flowing.getSource() : fluid;
        Double mj = table.get(BuiltInRegistries.FLUID.getKey(still).toString());
        return mj == null ? 0 : mj / mbPerM3;
    }

    /** What a building's input port may give its controller: the fuel its generator burns. */
    static boolean acceptsFuel(BuildingDef def, ItemStack stack) {
        return switch (def.power()) {
            case "biomass", "coal", "nuclear" -> itemMj(def.power(), stack) > 0;
            case "augmenter" -> itemMj("augmenter", stack) > 0;
            default -> false;
        };
    }

    /** mB in a cubic metre of fluid (siftec_content.json "mb_per_m3"). */
    public static int mbPerM3() {
        readFuel();
        return mbPerM3;
    }

    /** The waste a fuel rod leaves (its id and how many), or null if it leaves none. */
    public static String @Nullable [] wasteOf(ItemStack rod) {
        readFuel();
        String id = BuiltInRegistries.ITEM.getKey(rod.getItem()).toString();
        String waste = WASTE.get(id);
        return waste == null ? null : new String[]{waste, String.valueOf(WASTE_COUNT.getOrDefault(id, 1))};
    }

    /** True for the buildings that hold their grid's fuse: generators, the Augmenter and Power Storage. */
    public static boolean holdsFuse(BuildingDef def) {
        return switch (def.power()) {
            case "biomass", "coal", "fuel", "nuclear", "geothermal", "augmenter", "storage" -> true;
            default -> false;
        };
    }

    /** True if the waste a fuel rod leaves fits into the plant's output now. */
    static boolean wasteFits(ControllerBlockEntity c, ItemStack rod) {
        return wasteFits(c, rod, 0);
    }

    /**
     * True if the waste a fuel rod leaves fits into the plant's output, after {@code promised} waste still to come from
     * rods already handed to its fuel acceptors (they leave it as New Age uses them up).
     */
    static boolean wasteFits(ControllerBlockEntity c, ItemStack rod, int promised) {
        readFuel();
        String id = BuiltInRegistries.ITEM.getKey(rod.getItem()).toString();
        String waste = WASTE.get(id);
        if (waste == null) return true;
        Item wasteItem = BuiltInRegistries.ITEM.getValue(Identifier.parse(waste));
        int n = WASTE_COUNT.getOrDefault(id, 1);
        ItemStack out = c.output.getItem(0);
        if (!out.isEmpty() && !out.is(wasteItem)) return false;
        int max = out.isEmpty() ? wasteItem.getDefaultMaxStackSize() : out.getMaxStackSize();
        return out.getCount() + promised + n <= max;
    }

    /** The waste these rods will leave (a stack of rods in a fuel acceptor, not yet used up). */
    static int pendingWaste(ItemStack rods) {
        if (rods.isEmpty()) return 0;
        readFuel();
        String id = BuiltInRegistries.ITEM.getKey(rods.getItem()).toString();
        return WASTE.containsKey(id) ? WASTE_COUNT.getOrDefault(id, 1) * rods.getCount() : 0;
    }

    /** True if the Geothermal Generator's acid tank has room for a second's acid. */
    static boolean acidRoom(Level level, ControllerBlockEntity c) {
        return !tankFull(level, tank(c));
    }

    /**
     * A generator: its machines make the power ({@link Generators}), and it offers the grid what its coils' carbon brushes
     * hold this tick, no more. What the grid draws comes out of the brushes, and the share of it (the load) sets how fast
     * its fuel burns. Nothing is ever made here: with its machines stopped or broken it offers nothing.
     */
    static final class Generator extends FuseHolder {
        final ServerLevel level;
        final BuildingDef def;
        final double full;

        Generator(ServerLevel level, ControllerBlockEntity c, BuildingDef def) {
            super(c);
            this.level = level;
            this.def = def;
            this.full = Power.su(def.mw());
        }

        @Override
        public boolean boostable() {
            return true;
        }

        @Override
        public double capacity() {
            if (c.isRemoved() || c.tripped || c.broken) {
                c.offered = 0;
                return 0;
            }
            double su = Generators.offered(level, c);
            c.offered = su;
            c.problem = su > 0 ? "" : c.machineProblem.isEmpty() ? "siftec.power.starting" : c.machineProblem;
            return su;
        }

        /** Its machines have their fuel (and water) but make nothing yet: a boiler heating up, an engine spinning up. */
        @Override
        public boolean starting() {
            boolean ready = !c.isRemoved() && !c.tripped && !c.broken && c.offered <= 0 && c.machineProblem.isEmpty();
            // only for a few seconds after it last became ready: a machine stuck "starting" must not hold the fuse for ever
            long[] w = PowerMember.startWindow(ready, c.startingSince, level.getGameTime());
            c.startingSince = w[0];
            return w[1] == 1;
        }

        @Override
        public void generated(double su) {
            c.loadAt = level.getGameTime();
            c.load = c.offered > 0 ? (float) Math.min(1, Math.max(0, su / c.offered)) : 0;
            c.lastGenerated = su;
            if (su <= 0) return;
            Generators.take(level, c, su);
            c.energyGiven += su;
            if (def.power().equals("geothermal")) {
                c.fluidOwed += ACID_PER_SECOND / 20.0;
                long mb = (long) c.fluidOwed;
                if (mb > 0) {
                    fill(level, tank(c), "sulfuric_acid", mb);
                    c.fluidOwed -= mb;
                }
            }
        }

        @Override
        public void grid(GridStats stats) {
            super.grid(stats);
            if (stats.tripped || stats.produced <= 0) c.lastGenerated = 0;
        }

        /** SU-ticks of fuel it holds: in its machines (burner fuel, the engine's tank, the rods) and at its input. */
        @Override
        public double reserve() {
            String kind = def.power();
            if (kind.equals("geothermal")) return Double.POSITIVE_INFINITY;
            double r = 0;
            switch (kind) {
                case "biomass", "coal" -> {
                    List<BlockPos> burners = BuildingLogic.cells(c, Generators.block("create:blaze_burner"));
                    for (BlockPos pos : burners) {
                        if (level.isLoaded(pos) && level.getBlockEntity(pos) instanceof com.zurrtum.create.content.processing.burner.BlazeBurnerBlockEntity b) {
                            r += full / Math.max(1, burners.size()) * b.getRemainingBurnTime();
                        }
                    }
                }
                case "fuel" -> {
                    BlockPos at = BuildingLogic.cell(c, Generators.block("createdieselgenerators:diesel_engine"));
                    if (at != null && level.isLoaded(at) && level.getBlockEntity(at) instanceof EngineControl e && !e.siftec$fuel().isEmpty()) {
                        r += Power.suTicksOfMj(fluidMj(kind, e.siftec$fuel().getFluid()) * e.siftec$fuel().getAmount() / (FluidConstants.BUCKET / 1000.0));
                    }
                }
                case "nuclear" -> {
                    int rods = Math.max(1, BuildingLogic.cells(c, Generators.block("create_new_age:reactor_rod")).size());
                    r += full * Generators.rodFuel(level, c) / (double) rods;
                }
                default -> {
                }
            }
            ItemStack in = c.input.getItem(0);
            r += Power.suTicksOfMj(itemMj(kind, in) * in.getCount());
            return r;
        }

        /** Away, a Geothermal Generator gives its geyser's average, not whatever part of its swing it was at. */
        @Override
        public double steady(double capacity) {
            if (!def.power().equals("geothermal")) return capacity;
            BlockPos vent = vent(level, c);
            if (vent == null || c.problem.equals("siftec.power.acid_full")) return 0;
            return Power.su(switch (com.meakaandre.siftec.geyser.GeyserPurity.of(level, vent)) {
                case IMPURE -> 100.0;
                case PURE -> 400.0;
                default -> 200.0;
            });
        }

        /** Gives back energy the factory simulation took from its input but did not burn ({@link BuildingPower#refund}). */
        void refund(double suTicks, String lastFuel) {
            BuildingPower.refund(level, c, def, suTicks, lastFuel);
        }

        /** Burns what it gave while it was away: out of its machines' fuel first, then its input. */
        @Override
        public void spend(double suTicks) {
            String kind = def.power();
            if (suTicks <= 0 || kind.equals("geothermal")) return;
            double left = suTicks;
            switch (kind) {
                case "biomass", "coal" -> {
                    List<BlockPos> burners = BuildingLogic.cells(c, Generators.block("create:blaze_burner"));
                    double per = full / Math.max(1, burners.size());
                    for (BlockPos pos : burners) {
                        if (left <= 0) break;
                        if (!level.isLoaded(pos) || !(level.getBlockEntity(pos) instanceof com.zurrtum.create.content.processing.burner.BlazeBurnerBlockEntity b)) continue;
                        int ticks = (int) Math.min(b.getRemainingBurnTime(), Math.ceil(left / per));
                        ((com.meakaandre.siftec.mixin.building.BlazeBurnerAccessor) b).siftec$setRemainingBurnTime(b.getRemainingBurnTime() - ticks);
                        left -= ticks * per;
                    }
                }
                case "fuel" -> {
                    BlockPos at = BuildingLogic.cell(c, Generators.block("createdieselgenerators:diesel_engine"));
                    if (at != null && level.isLoaded(at) && level.getBlockEntity(at) instanceof EngineControl e && !e.siftec$fuel().isEmpty()) {
                        double perMb = fluidMj(kind, e.siftec$fuel().getFluid());
                        if (perMb > 0) {
                            long mb = (long) Math.ceil(left / Power.suTicksOfMj(perMb));
                            long took = drainAny(level, at, mb);
                            left -= Power.suTicksOfMj(perMb * took);
                        }
                    }
                }
                case "nuclear" -> {
                    // the rods burn a tick of fuel each for every tick at full power
                    long ticks = (long) Math.ceil(left / full);
                    for (BlockPos pos : BuildingLogic.cells(c, Generators.block("create_new_age:reactor_rod"))) {
                        if (level.isLoaded(pos) && level.getBlockEntity(pos) instanceof org.antarcticgardens.cna.content.nuclear.reactor.rod.ReactorRodBlockEntity rod) {
                            rod.fuel = (int) Math.max(0, rod.fuel - ticks);
                            rod.setChanged();
                        }
                    }
                    left = 0;
                }
                default -> {
                }
            }
            while (left > 0) {
                ItemStack in = c.input.getItem(0);
                double mj = itemMj(kind, in);
                if (mj <= 0) break;
                in.shrink(1);
                c.input.setChanged();
                c.made++;
                left -= Power.suTicksOfMj(mj);
            }
            c.setChanged();
        }
    }

    /**
     * Puts energy back into a generator's machines (SU-ticks): the unburnt part of the last fuel the factory simulation
     * took from its input while it was away. Into its Blaze Burners as burn time, its engine as the fuel it burns (or
     * the simulation's last fuel), its reactor rods as fuel ticks.
     */
    static void refund(ServerLevel level, ControllerBlockEntity c, BuildingDef def, double suTicks, String lastFuel) {
        if (suTicks <= 0) return;
        double full = Power.su(def.mw());
        switch (def.power()) {
            case "biomass", "coal" -> {
                List<BlockPos> burners = BuildingLogic.cells(c, Generators.block("create:blaze_burner"));
                double per = full / Math.max(1, burners.size());
                if (per <= 0 || burners.isEmpty()) return;
                int each = (int) Math.min(Integer.MAX_VALUE / 2, Math.floor(suTicks / per / burners.size()));
                for (BlockPos pos : burners) {
                    if (each <= 0 || !level.isLoaded(pos) || !(level.getBlockEntity(pos) instanceof com.zurrtum.create.content.processing.burner.BlazeBurnerBlockEntity b)) continue;
                    var access = (com.meakaandre.siftec.mixin.building.BlazeBurnerAccessor) b;
                    if (b.getRemainingBurnTime() <= 0) access.siftec$setActiveFuel(com.zurrtum.create.content.processing.burner.BlazeBurnerBlockEntity.FuelType.NORMAL);
                    access.siftec$setRemainingBurnTime(b.getRemainingBurnTime() + each);
                    b.setChanged();
                }
            }
            case "fuel" -> {
                BlockPos at = BuildingLogic.cell(c, Generators.block("createdieselgenerators:diesel_engine"));
                if (at == null || !level.isLoaded(at) || !(level.getBlockEntity(at) instanceof EngineControl e)) return;
                net.minecraft.world.level.material.Fluid fluid = e.siftec$fuel().isEmpty() ? com.meakaandre.siftec.sim.Keys.fluidOf(lastFuel) : e.siftec$fuel().getFluid();
                double perMb = fluidMj("fuel", fluid);
                Storage<FluidVariant> tank = ProductionLogic.fluids(level, at, null);
                if (perMb <= 0 || tank == null) return;
                long mb = (long) Math.floor(suTicks / Power.suTicksOfMj(perMb));
                if (mb <= 0) return;
                try (Transaction tx = Transaction.openOuter()) {
                    tank.insert(FluidVariant.of(fluid), mb * (FluidConstants.BUCKET / 1000), tx);
                    tx.commit();
                }
            }
            case "nuclear" -> {
                if (full <= 0) return;
                int ticks = (int) Math.min(Integer.MAX_VALUE / 2, Math.floor(suTicks / full));
                if (ticks <= 0) return;
                for (BlockPos pos : BuildingLogic.cells(c, Generators.block("create_new_age:reactor_rod"))) {
                    if (level.isLoaded(pos) && level.getBlockEntity(pos) instanceof org.antarcticgardens.cna.content.nuclear.reactor.rod.ReactorRodBlockEntity rod) {
                        rod.fuel = (int) Math.min(org.antarcticgardens.cna.content.nuclear.reactor.rod.ReactorRodBlockEntity.MAX_FUEL, (long) rod.fuel + ticks);
                        rod.setChanged();
                    }
                }
            }
            default -> {
            }
        }
        c.setChanged();
    }

    /** Drains mB of whatever fluid a block's tank holds; how many mB came out. */
    private static long drainAny(Level level, BlockPos pos, long mb) {
        Storage<FluidVariant> tank = ProductionLogic.fluids(level, pos, null);
        if (tank == null) return 0;
        long took = 0;
        try (Transaction tx = Transaction.openOuter()) {
            for (StorageView<FluidVariant> view : tank) {
                if (view.isResourceBlank()) continue;
                took += tank.extract(view.getResource(), (mb - took) * (FluidConstants.BUCKET / 1000), tx) / (FluidConstants.BUCKET / 1000);
                if (took >= mb) break;
            }
            tx.commit();
        }
        return took;
    }

    // ------------------------------------------------------------------------------------------------ augmenter

    static final class Augmenter extends FuseHolder {
        final ServerLevel level;
        final BuildingDef def;

        Augmenter(ServerLevel level, ControllerBlockEntity c, BuildingDef def) {
            super(c);
            this.level = level;
            this.def = def;
        }

        @Override
        public double capacity() {
            return c.isRemoved() || c.tripped ? 0 : Power.su(def.mw());
        }

        @Override
        public double boost() {
            if (c.isRemoved() || c.tripped) return 0;
            if (c.fedTicks <= 0) {
                ItemStack in = c.input.getItem(0);
                if (itemMj("augmenter", in) > 0) {
                    in.shrink(1);
                    c.input.setChanged();
                    c.fedTicks = MATRIX_TICKS;
                    c.made++;
                }
            }
            return c.fedTicks > 0 ? 0.3 : 0.1;
        }

        @Override
        public int boostTicks() {
            ItemStack in = c.input.getItem(0);
            return Math.max(0, c.fedTicks) + (itemMj("augmenter", in) > 0 ? in.getCount() * MATRIX_TICKS : 0);
        }

        @Override
        public void spendBoost(int ticks) {
            int left = ticks;
            while (left > 0) {
                if (c.fedTicks <= 0) {
                    ItemStack in = c.input.getItem(0);
                    if (itemMj("augmenter", in) <= 0) break;
                    in.shrink(1);
                    c.input.setChanged();
                    c.fedTicks = MATRIX_TICKS;
                    c.made++;
                }
                int n = Math.min(left, c.fedTicks);
                c.fedTicks -= n;
                left -= n;
            }
            c.setChanged();
        }

        @Override
        public void generated(double su) {
            if (c.fedTicks > 0) c.fedTicks--;
            c.lastGenerated = su;
            boolean on = su > 0;
            if (c.running != on) {
                c.running = on;
                visual(level, c, on);
            }
            c.setChanged();
        }
    }

    // ------------------------------------------------------------------------------------------------ storage

    static final class Store extends FuseHolder {
        Store(ControllerBlockEntity c) {
            super(c);
        }

        @Override
        public double stored() {
            return c.energy;
        }

        @Override
        public double storeCapacity() {
            return c.isRemoved() ? 0 : Power.su(STORAGE_MWH) * Power.TICKS_PER_HOUR;
        }

        @Override
        public double chargeRate() {
            return c.tripped ? 0 : Power.su(STORAGE_CHARGE_MW);
        }

        @Override
        public void store(double suTicks) {
            c.energy = Math.clamp(c.energy + suTicks, 0, storeCapacity());
            c.lastGenerated = -suTicks;
            c.setChanged();
        }
    }

    // ------------------------------------------------------------------------------------------------ fluids and looks

    /**
     * The tank a generator's fluid port works on (the Coal Generator's and the Nuclear Power Plant's water intake, the
     * Geothermal Generator's acid tank), or else its first Fluid Tank; null if it has none.
     */
    public static @Nullable BlockPos tank(ControllerBlockEntity c) {
        Placement p = c.placement();
        BuildingTemplate t = p == null ? null : BuildingTemplate.of(p.def());
        if (t != null) {
            for (BuildingTemplate.Cell cell : t.members) {
                if (cell.state().is(ModBlocks.BUILDING_PORT.get()) && cell.state().getValue(PortBlock.KIND).fluid() && cell.target() != null) {
                    return p.at(cell.target()[0], cell.target()[1], cell.target()[2]);
                }
            }
        }
        return BuildingLogic.cell(c, net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath("create", "fluid_tank")));
    }

    private static @Nullable Storage<FluidVariant> tankStorage(Level level, @Nullable BlockPos pos) {
        return pos == null ? null : FluidStorage.SIDED.find(level, pos, null);
    }

    private static boolean hasFluid(Level level, @Nullable BlockPos pos, Fluid fluid) {
        Storage<FluidVariant> tank = tankStorage(level, pos);
        if (tank == null) return false;
        for (StorageView<FluidVariant> view : tank) if (!view.isResourceBlank() && view.getResource().isOf(fluid) && view.getAmount() > 0) return true;
        return false;
    }

    private static void drain(Level level, @Nullable BlockPos pos, Fluid fluid, long mb) {
        Storage<FluidVariant> tank = tankStorage(level, pos);
        if (tank == null) return;
        try (Transaction tx = Transaction.openOuter()) {
            tank.extract(FluidVariant.of(fluid), mb * (FluidConstants.BUCKET / 1000), tx);
            tx.commit();
        }
    }

    private static void fill(Level level, @Nullable BlockPos pos, String fluidId, long mb) {
        FluidEntry entry = ModFluids.ALL.get(fluidId);
        Storage<FluidVariant> tank = tankStorage(level, pos);
        if (entry == null || tank == null) return;
        try (Transaction tx = Transaction.openOuter()) {
            tank.insert(FluidVariant.of(entry.still), mb * (FluidConstants.BUCKET / 1000), tx);
            tx.commit();
        }
    }

    private static boolean tankFull(Level level, @Nullable BlockPos pos) {
        Storage<FluidVariant> tank = tankStorage(level, pos);
        if (tank == null) return true;
        for (StorageView<FluidVariant> view : tank) if (view.getCapacity() - view.getAmount() >= FluidConstants.BUCKET / 1000 * ACID_PER_SECOND) return false;
        return true;
    }

    /** The geyser under a Geothermal Generator: below its open vent cell, through air and the geyser's water. */
    public static @Nullable BlockPos vent(Level level, ControllerBlockEntity c) {
        Placement p = c.placement();
        int[] v = p == null ? null : p.def().vent();
        return v == null ? null : Vents.findVent(level, p.at(v[0], v[1], v[2]));
    }

    /** A generator's look follows whether it runs: its fires (campfires, furnaces) are lit while it gives power. */
    static void visual(ServerLevel level, ControllerBlockEntity c, boolean on) {
        Placement p = c.placement();
        BuildingTemplate t = p == null ? null : BuildingTemplate.of(p.def());
        if (t == null) return;
        for (BuildingTemplate.Cell cell : t.members) {
            BlockPos pos = p.at(cell);
            BlockState state = level.getBlockState(pos);
            if ((state.getBlock() instanceof CampfireBlock || state.getBlock() instanceof AbstractFurnaceBlock)
                && state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT)
                && state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT) != on) {
                level.setBlock(pos, state.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT, on), net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ status

    /** The power part of a controller's status line: what it draws or gives, in SU, and its grid. */
    static void status(Level level, ControllerBlockEntity c, BuildingDef def, List<Component> parts) {
        switch (def.power()) {
            case "pole" -> {
            }
            case "storage" -> parts.add(Component.translatable("siftec.power.stored", Power.suHours(c.energy),
                Power.suHours(Power.su(STORAGE_MWH) * Power.TICKS_PER_HOUR)));
            case "augmenter" -> parts.add(Component.translatable("siftec.power.augmenter", Power.su((float) Power.su(def.mw())), c.fedTicks > 0 ? 30 : 10));
            case "biomass", "coal", "fuel", "nuclear", "geothermal" -> {
                parts.add(Component.translatable("siftec.power.gives", Power.su((float) c.lastGenerated), Power.su((float) c.offered)));
                if (def.power().equals("geothermal") && level instanceof ServerLevel server) {
                    BlockPos vent = vent(level, c);
                    if (vent != null) parts.add(Component.translatable("siftec.power.geyser", Component.translatable(com.meakaandre.siftec.geyser.GeyserPurity.of(server, vent).key())));
                }
            }
            default -> {
                if (def.su() > 0) parts.add(Component.translatable(!c.powered ? "siftec.power.no_power" : c.idle ? "siftec.building.idle" : "siftec.building.su", Power.fmt(def.su())));
            }
        }
        if (c.tripped) parts.add(Component.translatable("siftec.power.fuse_blown"));
        else if (c.grid != null && !def.power().equals("pole")) parts.add(c.grid.line());
    }

    /** Sneak-using a generator or Power Storage resets its grid's fuse. True if there was one to reset. */
    static boolean resetFuse(ServerLevel level, ControllerBlockEntity c) {
        PowerMember m = member(level, c);
        return m != null && m.holdsFuse() && PowerGrids.reset(level, m);
    }

    /** True if a kinetic block turns (for the turbine's coils, the selftests). */
    static boolean turning(Level level, @Nullable BlockPos pos) {
        return pos != null && level.getBlockEntity(pos) instanceof KineticBlockEntity k && k.getSpeed() != 0;
    }

    /** Satisfactory's waste for fuel rods a player-built New Age reactor took: next to its fuel acceptor, or on top. */
    public static void reactorWaste(Level level, BlockPos acceptor, ItemStack fuel, int used) {
        readFuel();
        String id = BuiltInRegistries.ITEM.getKey(fuel.getItem()).toString();
        String waste = WASTE.get(id);
        if (waste == null) return;
        ItemStack out = new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(waste)), WASTE_COUNT.getOrDefault(id, 1) * used);
        // the Nuclear Power Plant's own reactor: its waste goes to the plant's output, which the plant made room for
        BlockPos plant = BuildingIndex.controllerOf(level, acceptor);
        if (plant != null && level.getBlockEntity(plant) instanceof ControllerBlockEntity c) {
            ItemStack slot = c.output.getItem(0);
            c.wasteMade += out.getCount();
            if (slot.isEmpty()) {
                c.output.setItem(0, out.split(Math.min(out.getCount(), out.getMaxStackSize())));
            } else if (ItemStack.isSameItemSameComponents(slot, out)) {
                // never past a full stack (the plant takes no rod whose waste would not fit, counting what its acceptors hold)
                int move = Math.min(out.getCount(), slot.getMaxStackSize() - slot.getCount());
                if (move > 0) {
                    slot.grow(move);
                    out.shrink(move);
                }
            }
            if (!out.isEmpty()) net.minecraft.world.Containers.dropItemStack(level, plant.getX() + 0.5, plant.getY() + 1.1, plant.getZ() + 0.5, out.copy());
            c.output.setChanged();
            return;
        }
        for (net.minecraft.core.Direction side : net.minecraft.core.Direction.values()) {
            if (out.isEmpty()) break;
            var into = net.fabricmc.fabric.api.transfer.v1.item.ItemStorage.SIDED.find(level, acceptor.relative(side), side.getOpposite());
            if (into == null) continue;
            try (Transaction tx = Transaction.openOuter()) {
                long put = into.insert(net.fabricmc.fabric.api.transfer.v1.item.ItemVariant.of(out), out.getCount(), tx);
                tx.commit();
                out.shrink((int) put);
            }
        }
        if (!out.isEmpty()) net.minecraft.world.Containers.dropItemStack(level, acceptor.getX() + 0.5, acceptor.getY() + 1.1, acceptor.getZ() + 0.5, out);
    }
}
