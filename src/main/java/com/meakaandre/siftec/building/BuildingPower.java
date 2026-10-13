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
 * motors are given exactly the FE for its SU every tick, so they turn its machines; with none, they stop;</li>
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
    /** Satisfactory's geothermal output on a normal vent averages 200 MW; here a whole interval's worth comes out in a burst. */
    public static final double GEYSER_AVERAGE_MW = 200, GEYSER_BURST_MW = 2000, GEYSER_INTERVAL_S = 45;
    /** Sulfuric acid a second of eruption leaves in the Geothermal Generator's tank (the old Geyser Engine's rate). */
    public static final int ACID_PER_SECOND = 50;
    /** Ticks an Alien Power Matrix keeps an Augmenter fed (Satisfactory feeds it five a minute). */
    public static final int MATRIX_TICKS = 240;
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

        @Override
        public double demand() {
            return c.isRemoved() ? 0 : def.su();
        }

        @Override
        public void supplied(boolean on) {
            c.powered = on;
            if (!on) return;
            List<BlockPos> motors = motors(c);
            if (motors.isEmpty()) return;
            // each motor gets its share of the building's SU as FE; a New Age motor fed less than its full need gives
            // SU in proportion, so it gives exactly this share
            long fe = Math.round(Power.feOfSu(def.su() / (double) motors.size()));
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
    static double fluidMj(String kind, Fluid fluid) {
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

    /**
     * A generator. Its fuel is banked as energy (SU-ticks): an item or a little fluid is taken whenever the bank runs
     * low, and each tick the grid's draw comes off it. Water (the Coal Generator, the Nuclear Power Plant) is drawn from
     * its tank at Satisfactory's rate times the load; with none, it gives nothing.
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
            if (c.isRemoved() || c.tripped) return 0;
            c.problem = "";
            if (def.power().equals("geothermal")) return geyser();
            if (def.water() > 0 && !hasFluid(level, tank(c), Fluids.WATER)) {
                c.problem = "siftec.power.no_water";
                return 0;
            }
            if (c.energy < full && !refuel()) {
                if (c.energy <= 0) {
                    if (c.problem.isEmpty()) c.problem = "siftec.power.no_fuel";
                    return 0;
                }
                return Math.min(full, c.energy);
            }
            return full;
        }

        /** Takes the next fuel into the bank. False if there is none (or a reactor's waste has nowhere to go). */
        private boolean refuel() {
            String kind = def.power();
            if (kind.equals("fuel")) {
                // a bucket's worth at most at a time, so the bank never holds much more than the tank gave it
                Storage<FluidVariant> tank = tankStorage(level, tank(c));
                if (tank == null) return false;
                for (StorageView<FluidVariant> view : tank) {
                    if (view.isResourceBlank() || view.getAmount() < FluidConstants.BUCKET / 1000) continue;
                    double perMb = fluidMj(kind, view.getResource().getFluid());
                    if (perMb <= 0) continue;
                    long mb = Math.max(1, Math.min(view.getAmount() / (FluidConstants.BUCKET / 1000), 10));
                    try (Transaction tx = Transaction.openOuter()) {
                        long took = tank.extract(view.getResource(), mb * (FluidConstants.BUCKET / 1000), tx);
                        if (took <= 0) continue;
                        tx.commit();
                        c.energy += Power.suTicksOfMj(perMb * took / (FluidConstants.BUCKET / 1000.0));
                        c.setChanged();
                        return true;
                    }
                }
                c.problem = "siftec.power.no_fuel";
                return false;
            }
            ItemStack in = c.input.getItem(0);
            double mj = itemMj(kind, in);
            if (mj <= 0) return false;
            String id = BuiltInRegistries.ITEM.getKey(in.getItem()).toString();
            String waste = WASTE.get(id);
            if (waste != null) {
                Item wasteItem = BuiltInRegistries.ITEM.getValue(Identifier.parse(waste));
                ItemStack out = c.output.getItem(0);
                int n = WASTE_COUNT.getOrDefault(id, 1);
                if (!out.isEmpty() && (!out.is(wasteItem) || out.getCount() + n > out.getMaxStackSize())) {
                    c.problem = "siftec.power.waste_full";
                    return false;
                }
                if (out.isEmpty()) c.output.setItem(0, new ItemStack(wasteItem, n));
                else out.grow(n);
                c.output.setChanged();
            }
            in.shrink(1);
            c.input.setChanged();
            c.energy += Power.suTicksOfMj(mj);
            c.made++;
            c.setChanged();
            return true;
        }

        /** While the geyser under it erupts: a burst at 2,000 MW until an interval's worth of energy is out. */
        private double geyser() {
            BlockPos vent = vent(level, c);
            if (vent == null) {
                c.problem = "siftec.power.no_geyser";
                return 0;
            }
            if (tankFull(level, tank(c))) {
                c.problem = "siftec.power.acid_full";
                return 0;
            }
            BlockState state = level.getBlockState(vent);
            boolean erupting = Vents.gasEscapes(level, vent) && state.getValue(PotentSulfurBlock.STATE) == PotentSulfurState.ERUPTING;
            if (!erupting) {
                if (c.erupting) {
                    c.erupting = false;
                    c.setChanged();
                }
                c.energy = 0;
                c.problem = "siftec.power.waiting";
                return 0;
            }
            if (!c.erupting) {
                // a new eruption: one interval of the average output, to come out as a burst
                c.erupting = true;
                c.made++;
                c.energy = Power.su(GEYSER_AVERAGE_MW) * GEYSER_INTERVAL_S * 20;
                c.setChanged();
            }
            return c.energy <= 0 ? 0 : Math.min(Power.su(GEYSER_BURST_MW), c.energy);
        }

        @Override
        public void generated(double su) {
            if (su <= 0) {
                lit(false);
                return;
            }
            c.energy = Math.max(0, c.energy - su);
            c.lastGenerated = su;
            if (def.water() > 0) {
                // m3 a minute at full load, as mB a tick at this load
                c.fluidOwed += def.water() * mbPerM3 / 1200.0 * (su / full);
                long mb = (long) c.fluidOwed;
                if (mb > 0) {
                    drain(level, tank(c), Fluids.WATER, mb);
                    c.fluidOwed -= mb;
                }
            }
            if (def.power().equals("geothermal")) {
                c.fluidOwed += ACID_PER_SECOND / 20.0;
                long mb = (long) c.fluidOwed;
                if (mb > 0) {
                    fill(level, tank(c), "sulfuric_acid", mb);
                    c.fluidOwed -= mb;
                }
            }
            lit(true);
        }

        @Override
        public void grid(GridStats stats) {
            super.grid(stats);
            if (stats.tripped || stats.produced <= 0) c.lastGenerated = 0;
        }

        private void lit(boolean on) {
            if (c.running == on) return;
            c.running = on;
            c.setChanged();
            BuildingPower.visual(level, c, on);
        }
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

    /** The fluid tank inside a building (water, fuel or acid), or null. */
    static @Nullable BlockPos tank(ControllerBlockEntity c) {
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

    /**
     * A generator's look follows whether it runs: its fires (campfires, furnaces) are lit and its turbine turns the
     * generator coils while it gives power.
     */
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
            if (state.is(ModBlocks.TURBINE.get()) && level.getBlockEntity(pos) instanceof TurbineBlockEntity turbine) turbine.run(on);
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
            case "biomass", "coal", "fuel", "nuclear", "geothermal" -> parts.add(Component.translatable("siftec.power.gives",
                Power.su((float) c.lastGenerated), Power.su((float) Power.su(def.power().equals("geothermal") ? GEYSER_BURST_MW : def.mw()))));
            default -> {
                if (def.su() > 0) parts.add(Component.translatable(c.powered ? "siftec.building.su" : "siftec.power.no_power", Power.fmt(def.su())));
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
