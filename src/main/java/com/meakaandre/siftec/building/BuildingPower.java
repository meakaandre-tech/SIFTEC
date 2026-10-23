package com.meakaandre.siftec.building;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.meakaandre.siftec.fluid.FluidEntry;
import com.meakaandre.siftec.fluid.ModFluids;
import com.meakaandre.siftec.geyser.GeyserPurity;
import com.meakaandre.siftec.geyser.Vents;
import com.meakaandre.siftec.hub.Milestones;
import com.meakaandre.siftec.power.Power;
import com.meakaandre.siftec.power.PowerGrids;
import com.meakaandre.siftec.power.PowerMember;
import com.meakaandre.siftec.registry.ModBlocks;
import com.meakaandre.siftec.util.Transfers;
import com.zurrtum.create.content.kinetics.base.KineticBlockEntity;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import org.antarcticgardens.cna.content.motor.MotorBlock;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    /** Sulfuric acid a second of running leaves in the Geothermal Generator's tank. */
    public static final int ACID_PER_SECOND = 50;
    /** Ticks an Alien Power Matrix keeps an Augmenter fed (Satisfactory feeds it five a minute). */
    public static final int MATRIX_TICKS = 240;
    /** What an idle powered building draws: next to nothing (Satisfactory's idle machines draw none). */
    public static final double STANDBY_SU = 1;
    /** Satisfactory's Power Storage: 100 MWh, charged at up to 100 MW. */
    public static final double STORAGE_MWH = 100, STORAGE_CHARGE_MW = 100;
    /** mB in a cubic metre of a Satisfactory fluid when siftec_content.json does not say ("mb_per_m3"). */
    static final int DEFAULT_MB_PER_M3 = 40;
    /** The Augmenter's boost to its grid's capacity: with an Alien Power Matrix burning, and without. */
    static final double AUGMENTER_FED_BOOST = 0.3, AUGMENTER_BOOST = 0.1;
    /** The same boosts in percent, as its status shows them. */
    private static final int AUGMENTER_FED_BOOST_PERCENT = 30, AUGMENTER_BOOST_PERCENT = 10;

    private BuildingPower() {
    }

    /** The building's part in its grid, or null if it has none (a pole, storage containers, the Portable Miner). */
    public static @Nullable PowerMember member(ServerLevel level, ControllerBlockEntity c) {
        BuildingDef def = c.def();
        if (def == null) return null;
        if (c.member != null && c.memberFor == def) return c.member;
        PowerMember m = switch (def.power()) {
            case BIOMASS, COAL, FUEL, NUCLEAR, GEOTHERMAL -> new PowerGenerator(level, c, def);
            case AUGMENTER -> new PowerAugmenter(level, c, def);
            case STORAGE -> new PowerStore(c);
            case POLE -> null;
            default -> def.su() > 0 ? new PowerConsumer(level, c, def) : null;
        };
        c.member = m;
        c.memberFor = def;
        return m;
    }

    // ------------------------------------------------------------------------------------------------ consumers

    /** The New Age motors of a building. */
    public static List<BlockPos> motors(ControllerBlockEntity c) {
        List<BlockPos> out = new ArrayList<>();
        Placement p = c.placement();
        BuildingTemplate t = p == null ? null : BuildingTemplate.of(p.def());
        if (t == null) return out;
        for (BuildingTemplate.Cell cell : t.members) if (cell.state().getBlock() instanceof MotorBlock) out.add(p.at(cell));
        return out;
    }

    // ------------------------------------------------------------------------------------------------ generators

    /** What a generator burns: item (or tag, "#...") or fluid id, to megajoules (a fluid's per cubic metre). */
    private static final Map<String, Map<String, Double>> FUEL = new HashMap<>();
    /** Nuclear rods: the waste each leaves, and how many. */
    static final Map<String, String> WASTE = new HashMap<>();
    static final Map<String, Integer> WASTE_COUNT = new HashMap<>();
    private static int mbPerM3 = DEFAULT_MB_PER_M3;
    private static boolean read;

    public static void forget() {
        read = false;
        FUEL.clear();
        WASTE.clear();
        WASTE_COUNT.clear();
    }

    static synchronized void readFuel() {
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

    /** The fluids a generator of this kind burns (FUEL: the Fuel Generator's), by id. */
    public static Set<String> fluidFuels(PowerRole kind) {
        readFuel();
        return Set.copyOf(FUEL.getOrDefault(kind.id(), Map.of()).keySet());
    }

    /** Megajoules of one item of this fuel in a generator of this kind, or 0 if it does not burn it. */
    public static double itemMj(PowerRole kind, ItemStack stack) {
        readFuel();
        if (stack.isEmpty()) return 0;
        Map<String, Double> table = FUEL.getOrDefault(kind.id(), Map.of());
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        Double mj = table.get(id);
        if (mj != null) return mj;
        for (Map.Entry<String, Double> e : table.entrySet()) {
            if (e.getKey().startsWith("#") && stack.is(TagKey.create(Registries.ITEM, Identifier.parse(e.getKey().substring(1))))) {
                return e.getValue();
            }
        }
        return 0;
    }

    /** Megajoules of one mB of this fluid in a generator of this kind, or 0. */
    public static double fluidMj(PowerRole kind, Fluid fluid) {
        readFuel();
        Map<String, Double> table = FUEL.getOrDefault(kind.id(), Map.of());
        Fluid still = fluid instanceof FlowingFluid flowing ? flowing.getSource() : fluid;
        Double mj = table.get(BuiltInRegistries.FLUID.getKey(still).toString());
        return mj == null ? 0 : mj / mbPerM3;
    }

    /** What a building's input port may give its controller: the fuel its generator burns. */
    static boolean acceptsFuel(BuildingDef def, ItemStack stack) {
        return switch (def.power()) {
            case BIOMASS, COAL, NUCLEAR -> itemMj(def.power(), stack) > 0;
            case AUGMENTER -> itemMj(PowerRole.AUGMENTER, stack) > 0;
            default -> false;
        };
    }

    /** mB in a cubic metre of fluid (siftec_content.json "mb_per_m3"). */
    public static int mbPerM3() {
        readFuel();
        return mbPerM3;
    }

    /** True for the buildings that hold their grid's fuse: generators, the Augmenter and Power Storage. */
    public static boolean holdsFuse(BuildingDef def) {
        return def.power().holdsFuse();
    }

    /**
     * How much waste a Nuclear Power Plant's output holds (its output slot and its waste store), as Satisfactory's
     * plant: ten Uranium Fuel Rods' worth, so it keeps refuelling while its waste waits to be carried away.
     */
    public static final int WASTE_ROOM = 500;

    /** True if the Geothermal Generator's acid tank has room for a second's acid. */
    static boolean acidRoom(Level level, ControllerBlockEntity c) {
        return !tankFull(level, tank(c));
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
        return BuildingLogic.cell(c, BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath("create", "fluid_tank")));
    }

    private static @Nullable Storage<FluidVariant> tankStorage(Level level, @Nullable BlockPos pos) {
        return pos == null ? null : FluidStorage.SIDED.find(level, pos, null);
    }

    static void fill(Level level, @Nullable BlockPos pos, String fluidId, long mb) {
        FluidEntry entry = ModFluids.ALL.get(fluidId);
        Storage<FluidVariant> tank = tankStorage(level, pos);
        if (entry == null || tank == null) return;
        Transfers.insert(tank, FluidVariant.of(entry.still), mb * ProductionLogic.MB);
    }

    private static boolean tankFull(Level level, @Nullable BlockPos pos) {
        Storage<FluidVariant> tank = tankStorage(level, pos);
        if (tank == null) return true;
        for (StorageView<FluidVariant> view : tank) if (view.getCapacity() - view.getAmount() >= ProductionLogic.MB * ACID_PER_SECOND) return false;
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
                && state.hasProperty(BlockStateProperties.LIT)
                && state.getValue(BlockStateProperties.LIT) != on) {
                level.setBlock(pos, state.setValue(BlockStateProperties.LIT, on), Block.UPDATE_CLIENTS);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ status

    /** The power part of a controller's status line: what it draws or gives, in SU, and its grid. */
    static void status(Level level, ControllerBlockEntity c, BuildingDef def, List<Component> parts) {
        switch (def.power()) {
            case POLE -> {
            }
            case STORAGE -> parts.add(Component.translatable("siftec.power.stored", Power.suHours(c.energy),
                Power.suHours(Power.su(STORAGE_MWH) * Power.TICKS_PER_HOUR)));
            case AUGMENTER -> parts.add(Component.translatable("siftec.power.augmenter", Power.su((float) Power.su(def.mw())),
                c.fedTicks > 0 ? AUGMENTER_FED_BOOST_PERCENT : AUGMENTER_BOOST_PERCENT));
            case BIOMASS, COAL, FUEL, NUCLEAR, GEOTHERMAL -> {
                parts.add(Component.translatable("siftec.power.gives", Power.su((float) c.lastGenerated), Power.su((float) c.offered)));
                if (def.power() == PowerRole.GEOTHERMAL && level instanceof ServerLevel server) {
                    BlockPos vent = vent(level, c);
                    if (vent != null) parts.add(Component.translatable("siftec.power.geyser", Component.translatable(GeyserPurity.of(server, vent).key())));
                }
            }
            default -> {
                if (def.su() > 0) parts.add(Component.translatable(!c.powered ? "siftec.power.no_power" : c.idle ? "siftec.building.idle" : "siftec.building.su", Power.fmt(def.su())));
            }
        }
        if (c.tripped) parts.add(Component.translatable("siftec.power.fuse_blown"));
        else if (c.grid != null && def.power() != PowerRole.POLE) parts.add(c.grid.line());
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
}
