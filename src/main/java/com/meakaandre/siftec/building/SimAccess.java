package com.meakaandre.siftec.building;

import com.google.gson.JsonObject;
import com.meakaandre.siftec.hub.Milestone;
import com.meakaandre.siftec.hub.Milestones;
import com.meakaandre.siftec.machine.ProcessorRecipe;
import com.meakaandre.siftec.node.NodeRegrowth;
import com.meakaandre.siftec.node.NodeSites;
import com.meakaandre.siftec.node.Purity;
import com.meakaandre.siftec.power.Power;
import com.meakaandre.siftec.power.PowerMember;
import com.meakaandre.siftec.sim.Keys;
import com.meakaandre.siftec.sim.NodeMode;
import com.meakaandre.siftec.sim.Recipes;
import com.meakaandre.siftec.sim.SimData;
import com.meakaandre.siftec.sink.Sink;
import com.meakaandre.siftec.util.Chunks;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * What the background simulation ({@link com.meakaandre.siftec.sim}) needs to know about a building, read on the server
 * thread while the building is loaded: its recipes at Satisfactory's times (the same tables its machines keep to), its
 * ports, its wire connectors and its power role; and, when its chunk ticks again, how to hand back what changed (what it
 * made and mined, the fuel it burnt, its stored charge and fuse).
 */
public final class SimAccess {
    /** Ticks a miner takes beyond its regrowth and break time (its Drill's own pace), as measured. */
    public static final int MINER_LAG = 2;
    /** Ticks the Smelter's Depot adds to each batch (putting it on and taking it off), as measured. */
    public static final int SMELTER_LAG = 11;
    /** Ticks after a Basin recipe starts until its result is in the Basin (the Press or Mixer working). */
    public static final int BASIN_OUT = 20;

    private SimAccess() {
    }

    /** One port of a building: where it is, which way it faces out, what it is, and the cell it works on. */
    public record Port(BlockPos pos, Direction facing, PortBlock.Kind kind, BlockPos target) {
    }

    /** Whether a building is simulated at all: not the HUB, the Gateway, vehicles or plain poles. */
    public static boolean simulated(@Nullable BuildingDef def) {
        return def != null && !def.structure() && !def.vehicle() && def.power() != PowerRole.POLE;
    }

    public static List<Port> ports(ControllerBlockEntity c) {
        List<Port> out = new ArrayList<>();
        Placement p = c.placement();
        BuildingTemplate t = p == null ? null : BuildingTemplate.of(p.def());
        if (t == null) return out;
        for (BuildingTemplate.Cell cell : t.members) {
            if (!(cell.state().getBlock() instanceof PortBlock)) continue;
            BlockPos pos = p.at(cell);
            int[] tg = cell.target();
            BlockPos target = tg == null ? null : p.at(tg[0], tg[1], tg[2]);
            if (target == null) continue;
            Direction facing = p.orient(cell.state().getValue(PortBlock.FACING));
            out.add(new Port(pos, facing, cell.state().getValue(PortBlock.KIND), target));
        }
        return out;
    }

    /** Every chunk the building stands in, as packed chunk positions, its controller's first. */
    public static List<Long> chunks(ControllerBlockEntity c) {
        LinkedHashSet<Long> out = new LinkedHashSet<>();
        out.add(ChunkPos.pack(c.getBlockPos()));
        Placement p = c.placement();
        BuildingTemplate t = p == null ? null : BuildingTemplate.of(p.def());
        if (t != null) for (BuildingTemplate.Cell cell : t.members) out.add(ChunkPos.pack(p.at(cell)));
        return new ArrayList<>(out);
    }

    /** Cells of a kind of block in the building (its vaults, its tanks). */
    public static List<BlockPos> cells(ControllerBlockEntity c, String blockId) {
        return BuildingLogic.cells(c, BuiltInRegistries.BLOCK.getValue(Identifier.parse(blockId)));
    }

    /** Its New Age wire connectors. */
    public static List<BlockPos> connectors(ControllerBlockEntity c) {
        return cells(c, "create_new_age:electrical_connector");
    }

    public static @Nullable Storage<FluidVariant> fluids(Level level, BlockPos pos, @Nullable Direction side) {
        return ProductionLogic.fluids(level, pos, side);
    }

    private static String key(Item item) {
        return Keys.of(item);
    }

    private static SimData.Recipe recipe(long ticks, long outDelay) {
        SimData.Recipe r = new SimData.Recipe();
        r.ticks = Math.max(1, ticks);
        r.outDelay = Math.max(0, Math.min(outDelay, r.ticks));
        return r;
    }

    private static void ingredient(SimData.Node n, Map<String, Long> into, String key, long count) {
        if (key.startsWith("#")) n.alts.put(key, new ArrayList<>(Recipes.expand(key)));
        into.merge(key, count, Long::sum);
    }

    // ------------------------------------------------------------------------------------------------ the model

    /** Fills in what the building does, as the simulation runs it. */
    public static void model(ServerLevel level, ControllerBlockEntity c, SimData.Node n) {
        BuildingDef def = c.def();
        if (def == null) return;
        Recipes.load(level.getServer());
        n.building = def.id();
        n.logic = def.logic();
        n.company = c.companyId;
        n.power = def.power();
        n.su = def.power() == PowerRole.NONE ? def.su() : 0;
        n.unload = c.unload;
        n.recipes.clear();
        n.alts.clear();
        n.connectors.clear();
        for (BlockPos pos : connectors(c)) n.connectors.add(pos.asLong());
        n.mode = NodeMode.NONE;
        switch (def.logic()) {
            case MINER, PORTABLE_MINER -> mine(level, c, def, n);
            case SMELTER -> smelter(n);
            case BASIN -> basin(c, def, n);
            case ASSEMBLY -> assembly(c, def, n);
            case PACKAGER -> packager(n);
            case PROCESSOR -> processor(c, def, n);
            case EXTRACTOR, WELL_EXTRACTOR -> extractor(level, c, def, n);
            case POWER -> generator(level, c, def, n);
            case SINK -> {
                n.mode = NodeMode.SINK;
                n.inCap = 64;
            }
            case STORAGE, TRUCK_STATION -> {
                n.mode = NodeMode.STORE;
                for (String fuel : BuildingPower.fluidFuels(PowerRole.FUEL)) {
                    Fluid f = BuiltInRegistries.FLUID.getValue(Identifier.parse(fuel));
                    n.fuelMj.put(Keys.fluid(f), BuildingPower.fluidMj(PowerRole.FUEL, f));
                }
            }
            default -> {
            }
        }
    }

    private static void mine(ServerLevel level, ControllerBlockEntity c, BuildingDef def, SimData.Node n) {
        n.mode = NodeMode.MINE;
        n.outCap = 64;
        var site = c.node == null || !level.isLoaded(c.node) ? null : NodeSites.at(level, c.node);
        if (site == null) {
            n.mineItem = "";
            return;
        }
        // what a break gives, on average: the node's own ore block's loot, mined with the miner's Fortune
        BlockState state = site.look.nodeState();
        Map<String, Long> got = new HashMap<>();
        int samples = 48;
        ItemStack tool = new ItemStack(Items.NETHERITE_PICKAXE);
        if (def.fortune() > 0) {
            var holder = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(Enchantments.FORTUNE);
            tool.enchant(holder, def.fortune());
        }
        for (int i = 0; i < samples; i++) {
            for (ItemStack drop : Block.getDrops(state, level, c.node, null, null, tool)) got.merge(Keys.of(drop), (long) drop.getCount(), Long::sum);
        }
        String best = "";
        long most = 0;
        for (Map.Entry<String, Long> e : got.entrySet()) if (e.getValue() > most) {
            most = e.getValue();
            best = e.getKey();
        }
        // keep the average already worked out for this node, so its fractions do not jump about each time
        double per = most / (double) samples;
        if (!best.equals(n.mineItem) || n.minePer <= 0) n.minePer = per;
        n.mineItem = best;
        Purity purity = site.purity;
        boolean powered = def.logic() == Logic.MINER;
        n.minePeriod = NodeRegrowth.ticks(purity) + Math.max(1, def.breakTicks()) + (powered ? MINER_LAG : 0);
        if (!powered) n.outCap = 64;
    }

    private static void smelter(SimData.Node n) {
        n.mode = NodeMode.SMELT;
        n.inCap = 64;
        n.outCap = 64;
        JsonObject raw = Milestones.raw();
        JsonObject times = raw != null && raw.has("smelter_ticks") ? raw.getAsJsonObject("smelter_ticks") : new JsonObject();
        for (String id : times.keySet()) {
            Recipes.Parsed p = Recipes.smelting(id);
            if (p == null || p.results().isEmpty()) continue;
            SimData.Recipe r = recipe(times.get(id).getAsInt(), times.get(id).getAsInt());
            r.in.put(id, 1L);
            r.out.putAll(p.results());
            r.batch = BuildingLogic.SMELTER_BATCH;
            r.batchExtra = SMELTER_LAG;
            r.id = p.id();
            n.recipes.add(r);
        }
    }

    private static Map<String, Integer> machineTicks() {
        JsonObject raw = Milestones.raw();
        JsonObject times = raw != null && raw.has("machine_ticks") ? raw.getAsJsonObject("machine_ticks") : new JsonObject();
        Map<String, Integer> out = new HashMap<>();
        for (String k : times.keySet()) out.put(k, times.get(k).getAsInt());
        return out;
    }

    /** A Constructor, Assembler, Foundry, Refinery or Blender: the Basin recipe its filter picks, at Satisfactory's time. */
    private static void basin(ControllerBlockEntity c, BuildingDef def, SimData.Node n) {
        n.mode = NodeMode.CRAFT;
        n.inCap = 9 * 64;
        n.outCap = 9 * 64;
        n.fluidInCap = 2000;
        n.fluidOutCap = 2000;
        String type = cells(c, "create:mechanical_press").isEmpty() ? "create:mixing" : "create:compacting";
        Map<String, Integer> ticks = machineTicks();
        List<Recipes.Parsed> candidates = new ArrayList<>();
        if (!c.filter.isEmpty()) {
            candidates.addAll(Recipes.making(type, BuiltInRegistries.ITEM.getKey(c.filter.getItem()).toString()));
        } else {
            candidates.addAll(Recipes.ofType(type));
        }
        candidates.removeIf(p -> !ticks.containsKey(p.id()) || !unlocked(c, p.id()));
        String own = "/" + def.id() + "/";
        candidates.sort(Comparator.comparing((Recipes.Parsed p) -> !p.id().contains(own)));
        if (c.filter.isEmpty()) candidates.removeIf(p -> !p.id().contains(own));
        for (Recipes.Parsed p : candidates) {
            int t = ticks.get(p.id());
            SimData.Recipe r = recipe(t, BASIN_OUT);
            r.id = p.id();
            for (String ing : p.ingredients()) ingredient(n, r.in, ing, 1);
            r.fin.putAll(p.fluidsIn());
            r.out.putAll(p.results());
            r.fout.putAll(p.fluidsOut());
            n.recipes.add(r);
        }
    }

    /** Whether the building's company may run a recipe (by its id), as RecipeLocks lets its machines. */
    private static boolean unlocked(ControllerBlockEntity c, String recipeId) {
        JsonObject raw = Milestones.raw();
        JsonObject locks = raw != null && raw.has("recipe_locks") ? raw.getAsJsonObject("recipe_locks") : null;
        if (locks == null || !locks.has(recipeId)) return true;
        var company = ProductionLogic.company(c);
        return company != null && company.has(locks.get(recipeId).getAsString());
    }

    /** The Manufacturer and the Quantum Encoder: the Core's inputs plus each step's item or fluid, the result at the line's end. */
    private static void assembly(ControllerBlockEntity c, BuildingDef def, SimData.Node n) {
        n.mode = NodeMode.CRAFT;
        n.inCap = 9 * 64;
        n.outCap = 64;
        n.fluidInCap = 2000;
        ProductionLogic.Assembly a = ProductionLogic.assembly(c, def);
        if (a == null) return;
        SimData.Recipe r = recipe(a.ticks(), a.ticks());
        r.id = def.id() + ":" + BuiltInRegistries.ITEM.getKey(a.result());
        String first = a.first().key();
        if (a.core()) {
            List<Recipes.Parsed> core = Recipes.making("create:mixing", first);
            if (core.isEmpty()) return;
            for (String ing : core.getFirst().ingredients()) ingredient(n, r.in, ing, 1);
            r.fin.putAll(core.getFirst().fluidsIn());
        } else {
            ingredient(n, r.in, first, 1);
        }
        for (ProductionLogic.Step s : a.steps()) {
            if (s.fill()) r.fin.merge(Keys.fluid(s.fluid()), (long) s.mb(), Long::sum);
            else if (s.item() != null) ingredient(n, r.in, s.item().key(), 1);
        }
        r.out.put(key(a.result()), (long) a.count());
        n.recipes.add(r);
    }

    private static void packager(SimData.Node n) {
        n.mode = NodeMode.CRAFT;
        n.inCap = 64;
        n.outCap = 64;
        n.fluidInCap = 4000;
        n.fluidOutCap = 4000;
        for (ProductionLogic.Pack p : ProductionLogic.packs()) {
            if (p.in() == Items.AIR || p.out() == Items.AIR) continue;
            SimData.Recipe r = recipe(p.ticks(), p.ticks());
            r.in.put(key(p.in()), 1L);
            r.out.put(key(p.out()), 1L);
            if (p.fill()) r.fin.put(Keys.fluid(p.fluid()), (long) p.mb());
            else r.fout.put(Keys.fluid(p.fluid()), (long) p.mb());
            n.recipes.add(r);
        }
    }

    private static void processor(ControllerBlockEntity c, BuildingDef def, SimData.Node n) {
        n.mode = NodeMode.CRAFT;
        n.inCap = 9 * 64;
        n.outCap = 64;
        n.fluidInCap = 4000;
        n.fluidOutCap = 4000;
        for (ProcessorRecipe p : ProductionLogic.processorRecipes(c, def)) {
            SimData.Recipe r = recipe(p.seconds() * 20L, p.seconds() * 20L);
            for (Milestone.Cost cost : p.inputs()) ingredient(n, r.in, cost.key(), cost.count());
            if (p.fluidIn() != null) r.fin.put("fluid:" + p.fluidIn(), (long) p.fluidInMb());
            if (p.outItem() != null) r.out.put(p.outItem().toString(), (long) p.outCount());
            if (p.fluidOut() != null) r.fout.put("fluid:" + p.fluidOut(), (long) p.fluidOutMb());
            n.recipes.add(r);
        }
    }

    private static void extractor(ServerLevel level, ControllerBlockEntity c, BuildingDef def, SimData.Node n) {
        n.mode = NodeMode.EXTRACT;
        n.fluidOut = Keys.fluid(ExtractorLogic.fluid(def));
        float rate = 1f;
        if (def.logic() == Logic.WELL_EXTRACTOR) {
            ExtractorLogic.Draw d = ExtractorLogic.well(level, c);
            rate = d.purity() == null ? 0 : d.purity().multiplier;
        } else if (def.node()) {
            Purity purity = ExtractorLogic.nodePurity(level, c, def);
            rate = purity == null ? 0 : purity.multiplier;
        }
        n.fluidRate = def.rate() * rate / 1200.0;
        n.fluidOutCap = 4000;
    }

    private static void generator(ServerLevel level, ControllerBlockEntity c, BuildingDef def, SimData.Node n) {
        PowerRole kind = def.power();
        n.fullSu = Power.su(def.mw());
        // the Coal Generator and the Nuclear Power Plant drink water at their intake: none, no power
        n.water = def.water() > 0 ? def.water() * BuildingPower.mbPerM3() / 1200.0 : 0;
        if (n.water > 0) n.fluidInCap = Math.max(n.fluidInCap, 4000);
        switch (kind) {
            case BIOMASS, COAL, NUCLEAR -> {
                n.mode = NodeMode.GEN;
                n.inCap = 64;
                // a nuclear plant's waste: its output slot and waste store (BuildingPower.WASTE_ROOM), several rods' worth
                n.outCap = kind == PowerRole.NUCLEAR ? BuildingPower.WASTE_ROOM : 64;
                ItemStack in = c.input.getItem(0);
                if (!in.isEmpty()) fuel(n, kind, in);
            }
            case FUEL -> {
                n.mode = NodeMode.GEN;
                n.fluidInCap = 4000;
                for (String fuel : BuildingPower.fluidFuels(PowerRole.FUEL)) {
                    Fluid f = BuiltInRegistries.FLUID.getValue(Identifier.parse(fuel));
                    n.fuelMj.put(Keys.fluid(f), BuildingPower.fluidMj(PowerRole.FUEL, f));
                }
            }
            case GEOTHERMAL -> {
                n.mode = NodeMode.GEN;
                PowerMember m = BuildingPower.member(level, c);
                n.fullSu = m == null ? 0 : m.steady(n.fullSu);
                n.fluidOut = "fluid:siftec:sulfuric_acid";
                n.fluidOutCap = 4000;
            }
            case AUGMENTER -> {
                n.mode = NodeMode.GEN;
                n.inCap = 64;
                ItemStack in = c.input.getItem(0);
                if (!in.isEmpty() && BuildingPower.itemMj(PowerRole.AUGMENTER, in) > 0) n.fuelMj.put(Keys.of(in), 1.0);
            }
            // Power Storage: charged and drained with its grid, wherever that grid is
            case STORAGE -> n.mode = NodeMode.BATTERY;
            default -> {
            }
        }
    }

    /** Notes what one item of this fuel is worth to a generator of this kind (and the waste a fuel rod leaves). */
    public static void fuel(SimData.Node n, PowerRole kind, ItemStack stack) {
        double mj = BuildingPower.itemMj(kind, stack);
        if (mj <= 0) return;
        String key = Keys.of(stack);
        n.fuelMj.put(key, kind == PowerRole.AUGMENTER ? 1.0 : mj);
        String[] waste = BuildingPower.wasteOf(stack);
        if (waste != null) {
            n.waste.put(key, waste[0]);
            n.wasteCount.put(key, Integer.parseInt(waste[1]));
        }
    }

    /** What an item is worth to a building the simulation feeds it into: a generator's fuel, a Sink's points. */
    public static void accepts(SimData.Node n, String itemKey) {
        ItemStack stack = Keys.stack(itemKey, 1);
        if (stack.isEmpty()) return;
        if (n.mode == NodeMode.GEN && n.power != PowerRole.NONE) fuel(n, n.power, stack);
        if (n.mode == NodeMode.SINK && Sink.accepts(stack)) n.worth.put(itemKey, Sink.worthOne(stack));
    }

    // ------------------------------------------------------------------------------------------------ handing back

    /** A generator's energy in its machines (SU-ticks), not counting the fuel waiting at its input. */
    public static double machineEnergy(ServerLevel level, ControllerBlockEntity c) {
        return machineEnergy(level, c, null, null);
    }

    /**
     * The same, reading its machines through {@code has} and {@code at} when given: a chunk that is unloading outright
     * is no longer the level's, but its block entities are still there, so a generator's fuel is read as it leaves.
     */
    public static double machineEnergy(ServerLevel level, ControllerBlockEntity c, @Nullable Predicate<BlockPos> has,
                                       @Nullable Function<BlockPos, BlockEntity> at) {
        PowerMember m = BuildingPower.member(level, c);
        BuildingDef def = c.def();
        if (m == null || def == null) return 0;
        double r;
        if (has != null && at != null && m instanceof BuildingPower.Generator g) {
            // a cell of it out of reach: its machines cannot be read now (NaN: keep what was read before)
            Placement p = c.placement();
            BuildingTemplate t = p == null ? null : BuildingTemplate.of(p.def());
            if (t != null) for (BuildingTemplate.Cell cell : t.members) if (!has.test(p.at(cell))) return Double.NaN;
            r = g.reserve(at);
        } else {
            // a cell of it is not loaded: its machines cannot be read now (NaN: keep what was read before)
            for (long chunk : chunks(c)) if (!Chunks.loaded(level, chunk)) return Double.NaN;
            r = m.reserve();
        }
        if (!Double.isFinite(r)) return 0;
        ItemStack in = c.input.getItem(0);
        return Math.max(0, r - Power.suTicksOfMj(BuildingPower.itemMj(def.power(), in) * in.getCount()));
    }

    /** What the simulation did, written into the building as its chunk ticks again. */
    public static void handBack(ServerLevel level, ControllerBlockEntity c, SimData.Node n) {
        c.made += n.made;
        c.mined += n.mined;
        BuildingDef def = c.def();
        if (def != null) {
            switch (def.power()) {
                case STORAGE -> c.energy = n.stored;
                case AUGMENTER -> c.fedTicks = (int) Math.min(Integer.MAX_VALUE, n.fed);
                case BIOMASS, COAL, FUEL, NUCLEAR -> {
                    double spent = n.energy0 - n.energy;
                    PowerMember m = BuildingPower.member(level, c);
                    if (spent > 0 && m != null) m.spend(spent);
                    // the last fuel the simulation took is not all burnt: what is left goes back into the machines
                    else if (spent < 0 && m instanceof BuildingPower.Generator g) g.refund(-spent, n.lastFuel);
                }
                default -> {
                }
            }
            if (BuildingPower.holdsFuse(def)) c.tripped = n.tripped;
        }
        // a recipe the simulation had under way: the building waits out the rest of its time, as its machines would
        if (n.busyUntil > level.getGameTime()) c.nextCraft = Math.max(c.nextCraft, n.busyUntil);
        c.setChanged();
    }

    /** Where a Portable Miner's ore lands (it drops it on the ground in front). */
    public static @Nullable BlockPos dropSpot(ControllerBlockEntity c) {
        return BuildingLogic.dropSpot(c);
    }

    /** The values the simulation starts from when the building's chunk stops ticking. */
    public static void snapshot(ServerLevel level, ControllerBlockEntity c, SimData.Node n) {
        snapshot(level, c, n, null, null);
    }

    /** The same, reading the machines through {@code has} and {@code at} when given (see machineEnergy). */
    public static void snapshot(ServerLevel level, ControllerBlockEntity c, SimData.Node n, @Nullable Predicate<BlockPos> has,
                                @Nullable Function<BlockPos, BlockEntity> at) {
        n.made = 0;
        n.mined = 0;
        n.given = 0;
        n.tripped = c.tripped;
        n.stored = c.energy;
        n.fed = n.fed0 = c.fedTicks;
        double energy = machineEnergy(level, c, has, at);
        if (!Double.isNaN(energy)) n.energy = n.energy0 = energy;
        else n.energy0 = n.energy;
        n.unload = c.unload;
        n.powered = c.powered || n.su <= 0;
        // a recipe still waiting out its time (BasinPace) keeps waiting
        n.busyUntil = c.nextCraft > level.getGameTime() ? c.nextCraft : Long.MIN_VALUE;
    }

    /** Points for what a Sink took. */
    public static long worth(String itemKey) {
        ItemStack stack = Keys.stack(itemKey, 1);
        return stack.isEmpty() ? 0 : Sink.worthOne(stack);
    }

    static Map<String, Long> copy(Map<String, Long> m) {
        return new LinkedHashMap<>(m);
    }
}
