package com.meakaandre.siftec.building;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.hub.Milestone;
import com.meakaandre.siftec.hub.Milestones;
import com.meakaandre.siftec.machine.ProcessorRecipe;
import com.meakaandre.siftec.mixin.building.BlazeBurnerAccessor;
import com.zurrtum.create.content.fluids.drain.ItemDrainBlockEntity;
import com.zurrtum.create.content.kinetics.belt.transport.TransportedItemStack;
import com.zurrtum.create.content.kinetics.deployer.DeployerBlockEntity;
import com.zurrtum.create.content.logistics.depot.DepotBlockEntity;
import com.zurrtum.create.content.processing.basin.BasinBlockEntity;
import com.zurrtum.create.content.processing.burner.BlazeBurnerBlockEntity;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Work package 9's production buildings, run by their controllers ({@link BuildingLogic#tick}):
 * <ul>
 * <li>heat: the Foundry's, Refinery's and Blender's Blaze Burner is kept lit while the building has power (Satisfactory's
 * machines are electric), so the Mixer over it runs its heated recipes;</li>
 * <li>assembly (the Manufacturer and the Quantum Encoder): the Mixer over the Brass Basin makes the recipe's Core (paced
 * by {@link BasinPace} on its own timer); the line takes one item each recipe time on a second timer
 * ({@link ControllerBlockEntity#lineNext}), so Core and line run side by side, each at Satisfactory's rate; the building puts the Core (or, for a recipe of single items, its first input) on the first Depot
 * of its line and moves it on from Depot to Depot as each step is done there: the Spout fills it, each Deployer adds the
 * last one of one input (the building hands each Deployer that input from the Basin), and the Quantum Encoder's Item
 * Drain takes the Dark Matter Residue off. The finished item goes to the building's output. Create's own recipes do the
 * work; the building only carries the item along;</li>
 * <li>packager: empty canisters and tanks go onto the Depot under the Spout, which fills them; packaged fluids go into
 * the Item Drain, which empties them; each at Satisfactory's time;</li>
 * <li>processor (the Particle Accelerator and the Converter): {@link Processors}, on their machines.</li>
 * </ul>
 */
public final class ProductionLogic {
    private static final long MB = FluidConstants.BUCKET / 1000;

    private ProductionLogic() {
    }

    static Block create(String id) {
        return BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath("create", id));
    }

    static @Nullable Company company(ControllerBlockEntity c) {
        return com.meakaandre.siftec.owner.Ownership.of(c);
    }

    /** True if the building's company may run a recipe with this lock ("" for none). */
    static boolean unlocked(@Nullable Company company, String lock) {
        return lock.isEmpty() || company != null && company.has(lock);
    }

    // ------------------------------------------------------------------------------------------------ heat

    /** Keeps the building's Blaze Burners lit (kindled: Create's "heated") while it has power; unpowered they go out. */
    static void heat(ServerLevel level, ControllerBlockEntity c) {
        if (!c.powered) return;
        for (BlockPos pos : BuildingLogic.cells(c, create("blaze_burner"))) {
            if (!(level.getBlockEntity(pos) instanceof BlazeBurnerBlockEntity burner)) continue;
            BlazeBurnerAccessor fuel = (BlazeBurnerAccessor) burner;
            fuel.siftec$setActiveFuel(BlazeBurnerBlockEntity.FuelType.NORMAL);
            // well over the 125 ticks under which it fades, so the Basin always sees it heated while the power is on
            fuel.siftec$setRemainingBurnTime(400);
            burner.updateBlockState();
        }
    }

    /**
     * True if the building's Basins hold something to work on: an item in an input slot, or fluid in an input tank (and,
     * with {@code outputs}, a made item waiting, as the Manufacturer's Core waits for its line).
     */
    static boolean basinHasInput(Level level, ControllerBlockEntity c, boolean outputs) {
        List<BlockPos> basins = BuildingLogic.cells(c, create("basin"));
        basins.addAll(BuildingLogic.cells(c, com.meakaandre.siftec.registry.ModBlocks.BRASS_BASIN.get()));
        for (BlockPos pos : basins) {
            if (!(level.getBlockEntity(pos) instanceof BasinBlockEntity basin)) continue;
            Container inv = basin.itemCapability;
            int to = Math.min(outputs ? 18 : 9, inv.getContainerSize());
            for (int i = 0; i < to; i++) if (!inv.getItem(i).isEmpty()) return true;
            if (basin.inputTank != null && !basin.inputTank.isEmpty()) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------------ assembly

    /** One assembly recipe (siftec_content.json "assembly", written by tools/recipes.py). */
    record Assembly(String building, Item result, int count, Milestone.Cost first, boolean core, List<Step> steps, int ticks,
                    @Nullable Item charged, String lock) {
    }

    /** A step: an item a Deployer adds, or a fluid the Spout fills in. */
    record Step(Milestone.@Nullable Cost item, @Nullable Fluid fluid, int mb) {
        boolean fill() {
            return item == null;
        }
    }

    private static List<Assembly> assemblies;

    static synchronized List<Assembly> assemblies() {
        if (assemblies != null) return assemblies;
        List<Assembly> out = new ArrayList<>();
        JsonObject raw = Milestones.raw();
        if (raw != null && raw.has("assembly")) {
            for (JsonElement e : raw.getAsJsonArray("assembly")) {
                JsonObject o = e.getAsJsonObject();
                List<Step> steps = new ArrayList<>();
                for (JsonElement s : o.getAsJsonArray("steps")) {
                    JsonObject st = s.getAsJsonObject();
                    if (st.has("item")) steps.add(new Step(new Milestone.Cost(st.get("item").getAsString(), 1), null, 0));
                    else steps.add(new Step(null, BuiltInRegistries.FLUID.getValue(Identifier.parse(st.get("fluid").getAsString())), st.get("mb").getAsInt()));
                }
                out.add(new Assembly(o.get("building").getAsString(), item(o.get("result").getAsString()), o.get("count").getAsInt(),
                    new Milestone.Cost(o.get("first").getAsString(), 1), o.get("core").getAsBoolean(), List.copyOf(steps), o.get("ticks").getAsInt(),
                    o.has("charged") ? item(o.get("charged").getAsString()) : null, o.has("lock") ? o.get("lock").getAsString() : ""));
            }
        }
        assemblies = out;
        return out;
    }

    static Item item(String id) {
        return BuiltInRegistries.ITEM.getOptional(Identifier.parse(id)).orElse(Items.AIR);
    }

    /** The recipe the building makes: one for its result filter it can run, an alternate its company picked first. */
    static @Nullable Assembly assembly(ControllerBlockEntity c, BuildingDef def) {
        if (c.filter.isEmpty()) return null;
        Company company = company(c);
        Assembly standard = null;
        for (Assembly a : assemblies()) {
            if (!a.building.equals(def.id()) || !c.filter.is(a.result) || !unlocked(company, a.lock)) continue;
            if (a.lock.startsWith("alt_")) return a;
            if (standard == null) standard = a;
        }
        return standard;
    }

    /** A place on the line: a Depot under a Deployer or the Spout, or the Item Drain. */
    record Station(BlockPos at, String kind, @Nullable BlockPos above) {
    }

    /** The line from the back (by the Basin) to the front: the Depots under the Spout and the Deployers, then the Item Drain. */
    static List<Station> stations(Level level, ControllerBlockEntity c) {
        List<Station> out = new ArrayList<>();
        Placement p = c.placement();
        BuildingTemplate t = p == null ? null : BuildingTemplate.of(p.def());
        if (t == null) return out;
        Block depot = create("depot"), drain = create("item_drain"), deployer = create("deployer"), spout = create("spout");
        List<BuildingTemplate.Cell> cells = new ArrayList<>();
        for (BuildingTemplate.Cell cell : t.members) if (cell.state().getBlock() == depot || cell.state().getBlock() == drain) cells.add(cell);
        cells.sort((x, y) -> Integer.compare(y.z(), x.z()));
        for (BuildingTemplate.Cell cell : cells) {
            BlockPos pos = p.at(cell);
            if (cell.state().getBlock() == drain) {
                out.add(new Station(pos, "drain", null));
                continue;
            }
            BlockPos up = p.at(cell.x(), cell.y() + 2, cell.z());
            Block above = level.getBlockState(up).getBlock();
            out.add(new Station(pos, above == spout ? "spout" : above == deployer ? "deployer" : "depot", up));
        }
        return out;
    }

    /** Which station each step of the recipe is done at, or null if the line cannot run it. */
    static int @Nullable [] route(Assembly a, List<Station> line) {
        int[] at = new int[a.steps.size()];
        int deployers = 0, spouts = 0;
        for (int i = 0; i < a.steps.size(); i++) {
            String kind = a.steps.get(i).fill() ? "spout" : "deployer";
            int nth = kind.equals("spout") ? spouts++ : deployers++;
            int found = -1, seen = 0;
            for (int s = 0; s < line.size(); s++) {
                if (!line.get(s).kind.equals(kind)) continue;
                if (seen++ == nth) {
                    found = s;
                    break;
                }
            }
            if (found < 0) return null;
            at[i] = found;
        }
        return at;
    }

    static @Nullable BasinBlockEntity basin(Level level, ControllerBlockEntity c) {
        BlockPos pos = BuildingLogic.cell(c, com.meakaandre.siftec.registry.ModBlocks.BRASS_BASIN.get());
        if (pos == null) pos = BuildingLogic.cell(c, create("basin"));
        return pos != null && level.getBlockEntity(pos) instanceof BasinBlockEntity b ? b : null;
    }

    /** Takes one item matching the cost out of the Basin's input slots (0 to 8), or its output slots (9 to 17). */
    static ItemStack takeFromBasin(BasinBlockEntity basin, Milestone.Cost cost, boolean output) {
        Container inv = basin.itemCapability;
        int from = output ? 9 : 0, to = output ? Math.min(18, inv.getContainerSize()) : 9;
        for (int i = from; i < to; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || !cost.matches(s)) continue;
            ItemStack one = s.split(1);
            inv.setItem(i, s.isEmpty() ? ItemStack.EMPTY : s);
            inv.setChanged();
            return one;
        }
        return ItemStack.EMPTY;
    }

    static ItemStack held(Level level, Station s) {
        if (s.kind.equals("drain")) {
            return level.getBlockEntity(s.at) instanceof ItemDrainBlockEntity d && d.heldItem != null ? d.heldItem.stack : ItemStack.EMPTY;
        }
        if (level.getBlockEntity(s.at) instanceof DepotBlockEntity depot && depot.getHeldItem() != null) return depot.getHeldItem().stack;
        return ItemStack.EMPTY;
    }

    static ItemStack lift(Level level, Station s) {
        if (s.kind.equals("drain")) {
            if (!(level.getBlockEntity(s.at) instanceof ItemDrainBlockEntity d) || d.heldItem == null) return ItemStack.EMPTY;
            ItemStack out = d.heldItem.stack.copy();
            d.heldItem = null;
            d.setChanged();
            d.notifyUpdate();
            return out;
        }
        if (!(level.getBlockEntity(s.at) instanceof DepotBlockEntity depot) || depot.getHeldItem() == null) return ItemStack.EMPTY;
        ItemStack out = depot.getHeldItem().stack.copy();
        depot.depotBehaviour.removeHeldItem();
        depot.notifyUpdate();
        return out;
    }

    static boolean put(Level level, Station s, ItemStack stack) {
        if (s.kind.equals("drain")) {
            if (!(level.getBlockEntity(s.at) instanceof ItemDrainBlockEntity d) || d.heldItem != null) return false;
            // in from above: it is drained in the middle and stays there, the cell below it being the floor
            d.setHeldItem(new TransportedItemStack(stack), Direction.UP);
            d.notifyUpdate();
            return true;
        }
        if (!(level.getBlockEntity(s.at) instanceof DepotBlockEntity depot) || depot.getHeldItem() != null) return false;
        depot.depotBehaviour.setCenteredHeldItem(new TransportedItemStack(stack));
        depot.notifyUpdate();
        return true;
    }

    /** Puts what is left of a finished item into the building's output; false if it does not fit. */
    static boolean deliver(ControllerBlockEntity c, ItemStack stack) {
        ItemStack out = c.output.getItem(0);
        if (out.isEmpty()) {
            c.output.setItem(0, stack.copy());
        } else if (ItemStack.isSameItemSameComponents(out, stack) && out.getCount() + stack.getCount() <= out.getMaxStackSize()) {
            out.grow(stack.getCount());
            c.output.setChanged();
        } else {
            return false;
        }
        return true;
    }

    static boolean outputHasRoom(ControllerBlockEntity c, ItemStack stack) {
        ItemStack out = c.output.getItem(0);
        return out.isEmpty() || ItemStack.isSameItemSameComponents(out, stack) && out.getCount() + stack.getCount() <= out.getMaxStackSize();
    }

    static void assemble(ServerLevel level, ControllerBlockEntity c, BuildingDef def) {
        Assembly a = assembly(c, def);
        if (a == null) {
            c.problem = c.filter.isEmpty() ? "siftec.building.no_filter_set" : "siftec.building.no_recipe";
            return;
        }
        List<Station> line = stations(level, c);
        int[] route = route(a, line);
        BasinBlockEntity basin = basin(level, c);
        if (route == null || basin == null) {
            c.problem = "siftec.building.no_recipe";
            return;
        }
        if (!c.powered) {
            c.problem = "siftec.building.stopped";
            return;
        }
        c.problem = "";
        while (c.line.size() < line.size()) c.line.add(ItemStack.EMPTY);
        // the Mixer only makes this recipe's Core (a recipe of single items has none: the filter then lets nothing mix)
        ItemStack want = new ItemStack(a.core ? a.first.icon().getItem() : a.result);
        if (!ItemStack.isSameItem(basin.getFilter().getFilter(), want)) basin.getFilter().setFilter(want);

        // the Deployers each hold the input of their step, handed over from the Basin
        for (int i = 0; i < a.steps.size(); i++) {
            Step step = a.steps.get(i);
            Station s = line.get(route[i]);
            if (step.fill() || s.above == null || !(level.getBlockEntity(s.above) instanceof DeployerBlockEntity deployer)) continue;
            if (!deployer.heldItem.isEmpty()) continue;
            Storage<ItemVariant> hand = ItemStorage.SIDED.find(level, s.above, null);
            if (hand == null) continue;
            ItemStack one = takeFromBasin(basin, step.item, false);
            if (one.isEmpty()) continue;
            long put;
            try (Transaction tx = Transaction.openOuter()) {
                put = hand.insert(ItemVariant.of(one), 1, tx);
                if (put == 1) tx.commit();
            }
            if (put != 1) giveBack(basin, one);
        }

        // from the front of the line back, each item whose step is done moves on: to its next step's station, into the
        // Item Drain, or out
        for (int i = a.steps.size() - 1; i >= 0; i--) {
            Station s = line.get(route[i]);
            ItemStack now = held(level, s);
            ItemStack entry = c.line.get(route[i]);
            if (now.isEmpty() || entry.isEmpty() || ItemStack.isSameItemSameComponents(now, entry)) continue;
            // the step is done
            if (i + 1 < a.steps.size()) {
                int next = route[i + 1];
                if (!held(level, line.get(next)).isEmpty()) continue;
                ItemStack moved = lift(level, s);
                if (put(level, line.get(next), moved)) {
                    c.line.set(route[i], ItemStack.EMPTY);
                    c.line.set(next, moved.copy());
                } else {
                    put(level, s, moved);
                }
            } else if (a.charged != null) {
                Station drain = drain(line);
                if (drain == null || !held(level, drain).isEmpty()) continue;
                ItemStack moved = lift(level, s);
                if (put(level, drain, moved)) {
                    c.line.set(route[i], ItemStack.EMPTY);
                } else {
                    put(level, s, moved);
                }
            } else if (outputHasRoom(c, now)) {
                ItemStack done = lift(level, s);
                deliver(c, done);
                c.line.set(route[i], ItemStack.EMPTY);
                c.made++;
            }
        }
        // the Item Drain has taken the residue off: the finished item goes out
        Station drain = drain(line);
        if (drain != null && a.charged != null) {
            ItemStack d = held(level, drain);
            if (!d.isEmpty() && d.is(a.result) && outputHasRoom(c, d)) {
                deliver(c, lift(level, drain));
                c.made++;
            }
        }

        // the next one onto the line: the Core the Mixer has made, or the recipe's first input
        if (a.steps.isEmpty()) return;
        Station first = line.get(route[0]);
        if (!held(level, first).isEmpty()) return;
        // one goes on each recipe time, so the line runs at Satisfactory's rate however fast the Mixer makes Cores. The line
        // keeps its own time: the Mixer's (nextCraft, BasinPace) paces the Cores alone, so neither waits for the other
        if (level.getGameTime() < c.lineNext) return;
        ItemStack next = takeFromBasin(basin, a.first, a.core);
        if (next.isEmpty()) return;
        if (put(level, first, next)) {
            c.line.set(route[0], next.copy());
            c.lineNext = level.getGameTime() + a.ticks;
            c.setChanged();
        } else {
            giveBack(basin, next);
        }
    }

    private static @Nullable Station drain(List<Station> line) {
        for (Station s : line) if (s.kind.equals("drain")) return s;
        return null;
    }

    /** An item taken out of the Basin that could not be used goes back into its input slots. */
    static void giveBack(BasinBlockEntity basin, ItemStack stack) {
        Container inv = basin.itemCapability;
        for (int i = 0; i < 9 && !stack.isEmpty(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) {
                inv.setItem(i, stack.copy());
                stack.setCount(0);
            } else if (ItemStack.isSameItemSameComponents(s, stack) && s.getCount() < s.getMaxStackSize()) {
                s.grow(1);
                stack.shrink(1);
            }
        }
        inv.setChanged();
    }

    // ------------------------------------------------------------------------------------------------ packager

    /** One Packager recipe: fill (a container on the Spout's Depot) or empty (a packaged fluid in the Item Drain). */
    record Pack(boolean fill, Item in, Fluid fluid, int mb, Item out, int ticks, String lock) {
    }

    private static List<Pack> packs;

    static synchronized List<Pack> packs() {
        if (packs != null) return packs;
        List<Pack> out = new ArrayList<>();
        JsonObject raw = Milestones.raw();
        if (raw != null && raw.has("packager")) {
            for (JsonElement e : raw.getAsJsonArray("packager")) {
                JsonObject o = e.getAsJsonObject();
                out.add(new Pack(o.get("kind").getAsString().equals("fill"), item(o.get("in").getAsString()),
                    BuiltInRegistries.FLUID.getValue(Identifier.parse(o.get("fluid").getAsString())), o.get("mb").getAsInt(), item(o.get("out").getAsString()),
                    o.get("ticks").getAsInt(), o.has("lock") ? o.get("lock").getAsString() : ""));
            }
        }
        packs = out;
        return out;
    }

    /** The fill recipe that packages this fluid, or null when the Packager has none. */
    private static @org.jspecify.annotations.Nullable Pack fillFor(Fluid fluid) {
        for (Pack p : packs()) if (p.fill && p.fluid == fluid && p.mb > 0 && p.out != net.minecraft.world.item.Items.AIR) return p;
        return null;
    }

    /** One packaged fluid of this fluid (what the Packager fills), or empty when it has no package. */
    public static ItemStack packaged(Fluid fluid) {
        Pack p = fillFor(fluid);
        return p == null ? ItemStack.EMPTY : new ItemStack(p.out);
    }

    /** mB of the fluid one package holds (0 when it has no package). */
    public static int packagedMb(Fluid fluid) {
        Pack p = fillFor(fluid);
        return p == null ? 0 : p.mb;
    }

    /** What the Packager's input port takes: empty containers and packaged fluids it has a recipe for. */
    static boolean packagerTakes(ItemStack stack) {
        for (Pack p : packs()) if (stack.is(p.in)) return true;
        return false;
    }

    /** True while something lies on the Packager's Depot or in its Item Drain. */
    static boolean packagerHolds(Level level, ControllerBlockEntity c) {
        BlockPos depotPos = BuildingLogic.cell(c, create("depot")), drainPos = BuildingLogic.cell(c, create("item_drain"));
        return depotPos != null && !held(level, new Station(depotPos, "depot", null)).isEmpty()
            || drainPos != null && !held(level, new Station(drainPos, "drain", null)).isEmpty();
    }

    static void pack(ServerLevel level, ControllerBlockEntity c) {
        BlockPos depotPos = BuildingLogic.cell(c, create("depot")), drainPos = BuildingLogic.cell(c, create("item_drain")),
            spoutPos = BuildingLogic.cell(c, create("spout"));
        Station depot = depotPos == null ? null : new Station(depotPos, "depot", null), drain = drainPos == null ? null : new Station(drainPos, "drain", null);
        if (depot == null || drain == null || spoutPos == null) return;
        // a finished item on either comes off into the output
        for (Station s : new Station[]{depot, drain}) {
            ItemStack now = held(level, s);
            if (now.isEmpty()) continue;
            boolean done = false;
            for (Pack p : packs()) if (now.is(p.out) && (p.fill == (s == depot))) done = true;
            if (done && outputHasRoom(c, now)) {
                deliver(c, lift(level, s));
                c.made++;
            }
        }
        if (!c.powered) {
            c.problem = "siftec.building.stopped";
            return;
        }
        c.problem = "";
        ItemStack in = c.input.getItem(0);
        if (in.isEmpty() || level.getGameTime() < c.nextCraft) return;
        Company company = company(c);
        Fluid inSpout = fluidAt(level, spoutPos);
        for (Pack p : packs()) {
            if (!in.is(p.in) || !unlocked(company, p.lock)) continue;
            if (p.fill) {
                // only the container the Spout's fluid fills goes on, so a canister never waits there for the wrong fluid
                if (inSpout != p.fluid || !held(level, depot).isEmpty()) continue;
                if (put(level, depot, in.split(1))) {
                    c.input.setChanged();
                    c.nextCraft = level.getGameTime() + p.ticks;
                }
                return;
            }
            if (!held(level, drain).isEmpty()) return;
            if (put(level, drain, in.split(1))) {
                c.input.setChanged();
                c.nextCraft = level.getGameTime() + p.ticks;
            }
            return;
        }
    }

    // ------------------------------------------------------------------------------------------------ fluids

    static @Nullable Storage<FluidVariant> fluids(Level level, @Nullable BlockPos pos) {
        return pos == null ? null : fluids(level, pos, null);
    }

    /**
     * The fluid storage of a block from a side: Fabric's lookup, or for blocks Create only gives its own fluid inventory
     * (the Item Drain), that inventory wrapped for Fabric.
     */
    @SuppressWarnings("unchecked")
    static @Nullable Storage<FluidVariant> fluids(Level level, BlockPos pos, @Nullable Direction side) {
        Storage<FluidVariant> s = FluidStorage.SIDED.find(level, pos, side);
        if (s != null) return s;
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof com.zurrtum.create.infrastructure.fluids.FluidInventoryProvider<?> provider) {
            var inv = provider.getFluidInventory(state, level, pos, level.getBlockEntity(pos), side);
            if (inv != null) return side == null ? com.zurrtum.create.infrastructure.transfer.FluidInventoryStorage.of(inv)
                : com.zurrtum.create.infrastructure.transfer.FluidInventoryStorage.of(inv, side);
        }
        return null;
    }

    /** The fluid in a tank or a Spout (the first it holds), or empty. */
    static Fluid fluidAt(Level level, @Nullable BlockPos pos) {
        Storage<FluidVariant> s = fluids(level, pos);
        if (s == null) return Fluids.EMPTY;
        for (StorageView<FluidVariant> v : s) if (!v.isResourceBlank() && v.getAmount() > 0) return v.getResource().getFluid();
        return Fluids.EMPTY;
    }

    static long mbAt(Level level, @Nullable BlockPos pos, Fluid fluid) {
        Storage<FluidVariant> s = fluids(level, pos);
        long n = 0;
        if (s != null) for (StorageView<FluidVariant> v : s) if (!v.isResourceBlank() && v.getResource().isOf(fluid)) n += v.getAmount();
        return n / MB;
    }

    /** Puts mB of a fluid into a tank; how many mB went in. */
    static long fill(Level level, @Nullable BlockPos pos, Fluid fluid, long mb, boolean simulate) {
        Storage<FluidVariant> s = fluids(level, pos);
        if (s == null || fluid == Fluids.EMPTY) return 0;
        try (Transaction tx = Transaction.openOuter()) {
            long put = s.insert(FluidVariant.of(fluid), mb * MB, tx);
            if (!simulate) tx.commit();
            return put / MB;
        }
    }

    static long drain(Level level, @Nullable BlockPos pos, Fluid fluid, long mb) {
        Storage<FluidVariant> s = fluids(level, pos);
        if (s == null) return 0;
        try (Transaction tx = Transaction.openOuter()) {
            long took = s.extract(FluidVariant.of(fluid), mb * MB, tx);
            tx.commit();
            return took / MB;
        }
    }

    // ------------------------------------------------------------------------------------------------ processor

    /** The recipes the building's filter picks that its company may run: by the item, or the bucket of the fluid, they make. */
    static List<ProcessorRecipe> processorRecipes(ControllerBlockEntity c, BuildingDef def) {
        List<ProcessorRecipe> out = new ArrayList<>();
        if (c.filter.isEmpty()) return out;
        Company company = company(c);
        for (ProcessorRecipe r : ProcessorRecipe.of(def.id())) {
            boolean makes = r.outItem() != null && c.filter.is(r.item())
                || r.fluidOut() != null && c.filter.is(ProcessorRecipe.fluid(r.fluidOut()).getBucket());
            if (makes && (r.alt() == null || company != null && company.has(r.alt()))) out.add(r);
        }
        // an alternate the company picked comes first
        out.sort(Comparator.comparing((ProcessorRecipe r) -> r.alt() == null));
        return out;
    }

    /** The inputs the recipes take, each once, in order: slot i of the nine takes input i mod n (as the old machine did). */
    static List<Milestone.Cost> processorInputs(ControllerBlockEntity c, BuildingDef def) {
        List<Milestone.Cost> out = new ArrayList<>();
        for (ProcessorRecipe r : processorRecipes(c, def)) {
            for (Milestone.Cost cost : r.inputs()) if (out.stream().noneMatch(k -> k.key().equals(cost.key()))) out.add(cost);
        }
        return out;
    }

    static boolean processorSlotTakes(ControllerBlockEntity c, int slot, ItemStack stack) {
        BuildingDef def = c.def();
        if (def == null || !def.logic().equals("processor") || stack.isEmpty()) return false;
        List<Milestone.Cost> inputs = processorInputs(c, def);
        return !inputs.isEmpty() && inputs.get(slot % inputs.size()).matches(stack);
    }

    // ------------------------------------------------------------------------------------------------ status

    static void status(Level level, ControllerBlockEntity c, BuildingDef def, List<Component> parts) {
        switch (def.logic()) {
            case "assembly", "processor" -> parts.add(c.filter.isEmpty() ? Component.translatable("siftec.building.no_filter")
                : Component.translatable("siftec.building.filter", c.filter.getHoverName()));
            default -> {
            }
        }
    }
}
