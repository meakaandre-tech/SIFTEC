package com.meakaandre.siftec.building;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.hub.Milestone;
import com.meakaandre.siftec.machine.ProcessorRecipe;
import com.meakaandre.siftec.power.Power;
import com.zurrtum.create.AllSynchedDatas;
import com.zurrtum.create.content.kinetics.belt.transport.TransportedItemStack;
import com.zurrtum.create.content.kinetics.crusher.CrushingWheelControllerBlock;
import com.zurrtum.create.content.kinetics.crusher.CrushingWheelControllerBlockEntity;
import com.zurrtum.create.content.kinetics.deployer.DeployerBlockEntity;
import com.zurrtum.create.content.kinetics.fan.processing.AllFanProcessingTypes;
import com.zurrtum.create.content.logistics.depot.DepotBlockEntity;
import com.zurrtum.create.content.processing.basin.BasinBlockEntity;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.antarcticgardens.cna.content.motor.MotorBlockEntity;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Optional;

/**
 * The Particle Accelerator and the Converter, run on their machines (the recipes are tools/recipes.py, processors):
 * <ul>
 * <li>both: the input ports fill a Brass Basin, where a Mixer packs one recipe's inputs into its Core at Satisfactory's
 * time per recipe ({@link BasinPace}); the building's filter picks the recipe, and the Basin's filter is set to its
 * Core;</li>
 * <li>Particle Accelerator: the building drops each Core into the column over an Encased Fan, which blows it up between
 * two Crushing Wheels; their crushing recipe gives the result, which comes out over the wheels and goes to the output;</li>
 * <li>Converter: the building puts each Core on the Depot; the Deployer over it adds the last of the recipe's second
 * input, and the Converter Cell a fluid is carried in, as the building hands it each one; the Encased Fan blowing
 * through soul fire haunts what lies there into the result. A fluid comes out as a charged cell, which the building
 * moves into the Item Drain: the Drain empties it into its tank (the fluid output port's) and the cell goes back.
 * Excited Photonic Matter takes no input: the empty cell goes on the Depot once each recipe time.</li>
 * </ul>
 * The building only carries things from one machine to the next; the machines do the work. Power follows Satisfactory's
 * curve for the recipe: from its least to its most over each cycle ({@link #demand}).
 */
public final class Processors {
    private Processors() {
    }

    static net.minecraft.world.level.block.Block create(String id) {
        return BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath("create", id));
    }

    public static Item cellItem() {
        return BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath("siftec", "converter_cell"));
    }

    /**
     * True for anything that is the Converter's cell in one of its states: the plain cell, a loaded one on its way through
     * a conversion ({@code loaded_conversion_*}) or a charged one carrying a fluid ({@code charged_*_cell}). The cell is part
     * of its machine: it is never given back on a dismantle or a spill.
     */
    public static boolean cellBearing(ItemStack stack) {
        if (stack.isEmpty()) return false;
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (!id.getNamespace().equals("siftec")) return false;
        String path = id.getPath();
        return path.equals("converter_cell") || path.startsWith("loaded_conversion_") || path.startsWith("charged_") && path.endsWith("_cell");
    }

    /** True if the item is what one of the Converter's recipes makes (a finished result, whatever the filter is now). */
    private static boolean anyResult(BuildingDef def, ItemStack stack) {
        for (ProcessorRecipe r : ProcessorRecipe.of(def.id())) if (r.outItem() != null && stack.is(ProcessorRecipe.item(r.outItem()))) return true;
        return false;
    }

    /** The recipe the building runs: its filter's, an alternate its company picked first. */
    public static @Nullable ProcessorRecipe recipe(ControllerBlockEntity c, BuildingDef def) {
        List<ProcessorRecipe> list = ProductionLogic.processorRecipes(c, def);
        return list.isEmpty() ? null : list.getFirst();
    }

    static void tick(ServerLevel level, ControllerBlockEntity c, BuildingDef def) {
        ProcessorRecipe r = recipe(c, def);
        BasinBlockEntity basin = ProductionLogic.basin(level, c);
        if (basin != null) emptyBuffer(c, basin);
        if (r == null || basin == null) {
            c.problem = c.filter.isEmpty() ? "siftec.building.no_filter_set" : "siftec.building.no_recipe";
            return;
        }
        // the Mixer makes only this recipe's Core (none for a recipe with no Core)
        ItemStack want = r.core() != null ? new ItemStack(ProcessorRecipe.item(r.core())) : new ItemStack(cellItem());
        if (!ItemStack.isSameItem(basin.getFilter().getFilter(), want)) basin.getFilter().setFilter(want);
        // a Mixer looks for its next recipe only when its Basin's contents change: one left waiting with a full Basin (the
        // inputs stopped coming while it waited out the last recipe) looks again once a second
        BlockPos mixer = BuildingLogic.cell(c, create("mechanical_mixer"));
        if (mixer != null && level.getGameTime() % 20 == 0 && level.getGameTime() >= c.nextCraft
            && level.getBlockEntity(mixer) instanceof com.zurrtum.create.content.kinetics.mixer.MechanicalMixerBlockEntity m && !m.running) {
            m.basinChecker.scheduleUpdate();
        }
        if (def.id().equals("particle_accelerator")) accelerate(level, c, r, basin);
        else convert(level, c, r, basin);
    }

    /** Ingredients a building from before kept in its controller go into its Basin, where they are used now. */
    private static void emptyBuffer(ControllerBlockEntity c, BasinBlockEntity basin) {
        for (int i = 0; i < c.buffer.getContainerSize(); i++) {
            ItemStack s = c.buffer.getItem(i);
            if (s.isEmpty()) continue;
            Storage<ItemVariant> into = ItemStorage.SIDED.find(basin.getLevel(), basin.getBlockPos(), Direction.UP);
            if (into == null) return;
            try (Transaction tx = Transaction.openOuter()) {
                long put = into.insert(ItemVariant.of(s), s.getCount(), tx);
                tx.commit();
                s.shrink((int) put);
            }
            c.buffer.setChanged();
        }
    }

    // ------------------------------------------------------------------------------------------------ power

    /**
     * SU the building draws now: Satisfactory's power for its recipe, rising from the least to the most over each recipe
     * cycle (from when its Mixer started the Core, or the cell went on the Depot), and back to the least for the next.
     */
    static double demand(ServerLevel level, ControllerBlockEntity c, BuildingDef def) {
        ProcessorRecipe r = recipe(c, def);
        if (r == null || r.mwMax() <= 0) return def.su();
        // the Core's real cycle: a packed Core (two or three crafts) rises over its whole time, once
        double ticks = r.cycleTicks();
        double into = (level.getGameTime() - (c.nextCraft - ticks)) / ticks;
        double t = into < 0 || into >= 1 ? into - Math.floor(into) : into;
        return Power.su(r.mwMin() + (r.mwMax() - r.mwMin()) * t);
    }

    /** True while it has something under way: a Core in its fan column, or anything on its Depot or in its Drain. */
    static boolean busy(Level level, ControllerBlockEntity c) {
        BlockPos depot = BuildingLogic.cell(c, create("depot")), drain = BuildingLogic.cell(c, create("item_drain"));
        if (depot != null && !ProductionLogic.held(level, new ProductionLogic.Station(depot, "depot", null)).isEmpty()) return true;
        if (drain != null && !ProductionLogic.held(level, new ProductionLogic.Station(drain, "drain", null)).isEmpty()) return true;
        BlockPos fan = BuildingLogic.cell(c, create("encased_fan"));
        BlockPos gap = gap(c);
        if (fan != null && gap != null) {
            if (level.getBlockEntity(gap) instanceof CrushingWheelControllerBlockEntity crusher && crusher.isOccupied()) return true;
            return !level.getEntitiesOfClass(ItemEntity.class, column(fan, gap)).isEmpty();
        }
        return false;
    }

    /**
     * True while a step of its recipe can go on (the building counts as working and draws its recipe's power): its Mixer
     * mixes or has every input, a Core is in the column or the wheels, or what lies on the Depot or in the Drain can move
     * on. A building starved of an input, or with its output full, is idle however much lies inside it.
     */
    static boolean progressing(ServerLevel level, ControllerBlockEntity c, BuildingDef def) {
        ProcessorRecipe r = recipe(c, def);
        BasinBlockEntity basin = ProductionLogic.basin(level, c);
        if (r == null || basin == null || c.problem.equals("siftec.building.full")) return false;
        BlockPos mixer = BuildingLogic.cell(c, create("mechanical_mixer"));
        if (mixer != null && level.getBlockEntity(mixer) instanceof com.zurrtum.create.content.kinetics.mixer.MechanicalMixerBlockEntity m && m.running) return true;
        if (hasInputs(basin, r)) return true;
        Item core = ProcessorRecipe.item(r.core());
        boolean coreWaiting = core != net.minecraft.world.item.Items.AIR && basinHolds(basin, core, true);
        if (def.id().equals("particle_accelerator")) {
            BlockPos fan = BuildingLogic.cell(c, create("encased_fan")), gap = gap(c);
            if (fan == null || gap == null) return false;
            if (level.getBlockEntity(gap) instanceof CrushingWheelControllerBlockEntity crusher && crusher.isOccupied()) return true;
            for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, column(fan, gap))) if (e.getItem().is(core)) return true;
            return coreWaiting && resultFits(c, r);
        }
        BlockPos depotPos = BuildingLogic.cell(c, create("depot")), drainPos = BuildingLogic.cell(c, create("item_drain")),
            deployerPos = BuildingLogic.cell(c, create("deployer"));
        if (depotPos == null || drainPos == null || deployerPos == null) return false;
        ProductionLogic.Station depot = new ProductionLogic.Station(depotPos, "depot", deployerPos), drain = new ProductionLogic.Station(drainPos, "drain", null);
        if (chargedCell(ProductionLogic.held(level, drain))) return true;
        if (level.getBlockEntity(deployerPos) instanceof DeployerBlockEntity d && !d.heldItem.isEmpty()) return true;
        ItemStack on = ProductionLogic.held(level, depot);
        if (on.isEmpty()) return r.core() != null ? coreWaiting : c.carrier.is(cellItem());
        if (r.outItem() != null && on.is(ProcessorRecipe.item(r.outItem())) || anyResult(def, on)) return ProductionLogic.outputHasRoom(c, on);
        if (chargedCell(on)) return ProductionLogic.held(level, drain).isEmpty();
        if (!stage(r, on)) return true;
        if (r.haunt() != null && on.is(ProcessorRecipe.item(r.haunt()))) return true;
        for (ProcessorRecipe.Step s : r.steps()) {
            if (!on.is(ProcessorRecipe.item(s.on()))) continue;
            if (s.apply().matches(new ItemStack(cellItem()))) return c.carrier.is(cellItem());
            Container inv = basin.itemCapability;
            for (int i = 0; i < 9; i++) if (s.apply().matches(inv.getItem(i))) return true;
            return false;
        }
        return false;
    }

    /** True if the Basin holds at least one of every item input of the recipe, and fluid when it takes one. */
    private static boolean hasInputs(BasinBlockEntity basin, ProcessorRecipe r) {
        if (r.inputs().isEmpty() && r.fluidIn() == null) return false;
        Container inv = basin.itemCapability;
        for (Milestone.Cost cost : r.inputs()) {
            boolean found = false;
            for (int i = 0; i < 9 && !found; i++) found = cost.matches(inv.getItem(i));
            if (!found) return false;
        }
        return r.fluidIn() == null || basin.inputTank != null && !basin.inputTank.isEmpty();
    }

    private static boolean basinHolds(BasinBlockEntity basin, Item item, boolean output) {
        Container inv = basin.itemCapability;
        int from = output ? 9 : 0, to = output ? Math.min(18, inv.getContainerSize()) : 9;
        for (int i = from; i < to; i++) if (inv.getItem(i).is(item)) return true;
        return false;
    }

    /** True for a Core of any of the Particle Accelerator's or the Converter's recipes. */
    public static boolean isCore(ItemStack stack) {
        if (stack.isEmpty()) return false;
        for (String machine : new String[]{"particle_accelerator", "converter"}) {
            for (ProcessorRecipe r : ProcessorRecipe.of(machine)) if (r.core() != null && stack.is(ProcessorRecipe.item(r.core()))) return true;
        }
        return false;
    }

    /** Whether the Converter's carrier, the Excited Photonic Matter recipe, can start now. */
    static boolean ready(ServerLevel level, ControllerBlockEntity c, BuildingDef def) {
        ProcessorRecipe r = recipe(c, def);
        return r != null && r.core() == null && c.carrier.is(cellItem());
    }

    // ------------------------------------------------------------------------------------------------ Particle Accelerator

    /** The cell between the two Crushing Wheels, where Create puts the crusher's own block. */
    static @Nullable BlockPos gap(ControllerBlockEntity c) {
        List<BlockPos> wheels = BuildingLogic.cells(c, create("crushing_wheel"));
        if (wheels.size() != 2) return null;
        BlockPos a = wheels.get(0), b = wheels.get(1);
        return new BlockPos((a.getX() + b.getX()) / 2, a.getY(), (a.getZ() + b.getZ()) / 2);
    }

    /** The air over the fan, up to over the wheels. */
    private static AABB column(BlockPos fan, BlockPos gap) {
        return new AABB(fan.above()).minmax(new AABB(gap.above(3))).inflate(0.45, 0, 0.45);
    }

    private static void accelerate(ServerLevel level, ControllerBlockEntity c, ProcessorRecipe r, BasinBlockEntity basin) {
        BlockPos fan = BuildingLogic.cell(c, create("encased_fan")), gap = gap(c);
        if (fan == null || gap == null) {
            c.problem = "siftec.building.no_recipe";
            return;
        }
        if (level.getBlockEntity(gap) instanceof CrushingWheelControllerBlockEntity crusher) {
            // the crusher's own block belongs to the company too, so it runs the recipes the company has
            if (!c.companyId.isEmpty() && !c.companyId.equals(crusher.getAttached(com.meakaandre.siftec.owner.Ownership.OWNER))) {
                crusher.setAttached(com.meakaandre.siftec.owner.Ownership.OWNER, c.companyId);
            }
            upward(level, c, gap);
        }
        lift(level, fan);
        // what the wheels crushed comes out over them, marked as theirs
        boolean out = false;
        int results = 0;
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, new AABB(gap).expandTowards(0, 3, 0).inflate(0.6))) {
            Optional<BlockPos> from = AllSynchedDatas.BYPASS_CRUSHING_WHEEL.get(e);
            if (from == null || from.isEmpty() || !from.get().equals(gap) || e.getItem().isEmpty()) continue;
            ItemStack stack = e.getItem();
            ItemStack slot = c.output.getItem(0);
            int fit = slot.isEmpty() ? stack.getCount() : ItemStack.isSameItemSameComponents(slot, stack) ? Math.min(stack.getCount(), slot.getMaxStackSize() - slot.getCount()) : 0;
            if (fit <= 0) {
                c.problem = "siftec.building.full";
                continue;
            }
            if (slot.isEmpty()) c.output.setItem(0, stack.copyWithCount(fit));
            else slot.grow(fit);
            c.output.setChanged();
            if (r.outItem() != null && stack.is(ProcessorRecipe.item(r.outItem()))) results += fit;
            stack.shrink(fit);
            if (stack.isEmpty()) e.discard();
            else e.setItem(stack);
            out = true;
        }
        if (out) {
            // crafts made: a Core carries one or more crafts' results
            c.made += Math.max(1, results / Math.max(1, r.outCount()));
            c.setChanged();
        }
        if (!c.powered) {
            if (c.problem.isEmpty()) c.problem = "siftec.building.stopped";
            return;
        }
        if (!out && c.problem.equals("siftec.building.full") && !resultFits(c, r)) return;
        c.problem = "";
        // the next Core into the fan's column, once the last has gone up into the wheels, and only while things move
        // there (an item in a chunk not ticking its entities would hang in the air, or be lost while it loads)
        if (!level.isPositionEntityTicking(fan.above())) return;
        Item core = ProcessorRecipe.item(r.core());
        if (core == net.minecraft.world.item.Items.AIR) return;
        boolean flying = false;
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, column(fan, gap))) {
            if (!e.getItem().is(core)) continue;
            if (e.tickCount > STUCK_TICKS) {
                // a Core that never reached the wheels (they stopped, or were rebuilt) goes back to wait in the Basin
                ItemStack left = returnToBasin(basin, e.getItem(), true);
                if (left.isEmpty()) e.discard();
                else e.setItem(left);
                continue;
            }
            flying = true;
        }
        if (flying) return;
        if (level.getBlockEntity(gap) instanceof CrushingWheelControllerBlockEntity crusher && crusher.isOccupied()) return;
        // only once what it makes fits in the output: a result with nowhere to go would lie there and despawn
        if (!resultFits(c, r)) {
            c.problem = "siftec.building.full";
            return;
        }
        ItemStack next = ProductionLogic.takeFromBasin(basin, new Milestone.Cost(BuiltInRegistries.ITEM.getKey(core).toString(), 1), true);
        if (next.isEmpty()) return;
        ItemEntity drop = new ItemEntity(level, fan.getX() + 0.5, fan.getY() + 1.05, fan.getZ() + 0.5, next);
        drop.setDeltaMovement(0, 0.1, 0);
        drop.setNeverPickUp();
        drop.setUnlimitedLifetime();
        level.addFreshEntity(drop);
        c.launched++;
    }

    /**
     * Two Crushing Wheels carry what is between them the way they turn against each other; the building's carry it up.
     * If they carry it down (a building turned or mirrored the other way), both turn the other way from then on.
     */
    private static void upward(ServerLevel level, ControllerBlockEntity c, BlockPos gap) {
        BlockState state = level.getBlockState(gap);
        if (!state.hasProperty(CrushingWheelControllerBlock.FACING) || !state.getValue(CrushingWheelControllerBlock.VALID)) return;
        if (state.getValue(CrushingWheelControllerBlock.FACING) != Direction.DOWN) return;
        for (BlockPos wheel : BuildingLogic.cells(c, create("crushing_wheel"))) {
            for (Direction d : Direction.values()) {
                if (level.getBlockEntity(wheel.relative(d)) instanceof MotorBlockEntity motor && motor.speedBehavior != null) {
                    motor.speedBehavior.setValue(-motor.speedBehavior.getValue());
                    motor.setChanged();
                }
            }
        }
        Siftec.LOGGER.info("SIFTEC: building {} at {}: its Crushing Wheels carried downward; both now turn the other way", c.buildingId, c.getBlockPos().toShortString());
    }

    /** The fan under the column runs at {@link BuildingLogic#LIFT_RPM} (a building from before ran it slower). */
    private static void lift(ServerLevel level, BlockPos fan) {
        for (Direction d : Direction.values()) {
            if (!(level.getBlockEntity(fan.relative(d)) instanceof MotorBlockEntity motor) || motor.speedBehavior == null) continue;
            int now = motor.speedBehavior.getValue();
            if (now != 0 && Math.abs(now) != (int) BuildingLogic.LIFT_RPM) {
                motor.speedBehavior.setValue((int) Math.signum(now) * (int) BuildingLogic.LIFT_RPM);
                motor.setChanged();
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ Converter

    private static void convert(ServerLevel level, ControllerBlockEntity c, ProcessorRecipe r, BasinBlockEntity basin) {
        BlockPos depotPos = BuildingLogic.cell(c, create("depot")), drainPos = BuildingLogic.cell(c, create("item_drain")),
            deployerPos = BuildingLogic.cell(c, create("deployer"));
        if (depotPos == null || drainPos == null || deployerPos == null) {
            c.problem = "siftec.building.no_recipe";
            return;
        }
        ProductionLogic.Station depot = new ProductionLogic.Station(depotPos, "depot", deployerPos), drain = new ProductionLogic.Station(drainPos, "drain", null);
        Item cell = cellItem();
        // the Item Drain has emptied a cell: it goes back to wait for the next
        ItemStack drained = ProductionLogic.held(level, drain);
        if (drained.is(cell)) {
            ItemStack back = ProductionLogic.lift(level, drain);
            if (c.carrier.isEmpty()) c.carrier = back.copyWithCount(1);
            c.setChanged();
        }
        // what came out of the haunting besides the main result waits in the Depot's output buffer
        if (level.getBlockEntity(depotPos) instanceof DepotBlockEntity d) {
            Container buffer = d.depotBehaviour.processingOutputBuffer;
            for (int i = 0; i < buffer.getContainerSize(); i++) {
                ItemStack s = buffer.getItem(i);
                if (s.isEmpty() || !ProductionLogic.outputHasRoom(c, s)) continue;
                ProductionLogic.deliver(c, s.copy());
                buffer.setItem(i, ItemStack.EMPTY);
                buffer.setChanged();
                d.notifyUpdate();
            }
        }
        ItemStack on = ProductionLogic.held(level, depot);
        Item charged = ProcessorRecipe.item(r.charged());
        if (!on.isEmpty()) {
            boolean result = r.outItem() != null && on.is(ProcessorRecipe.item(r.outItem()));
            if (on.is(cell) && r.core() != null) {
                // a cell left on the Depot (the recipe changed): it goes back
                ProductionLogic.lift(level, depot);
                if (c.carrier.isEmpty()) c.carrier = new ItemStack(cell);
            } else if (charged != net.minecraft.world.item.Items.AIR && on.is(charged) || chargedCell(on)) {
                // a charged cell into the Item Drain, which empties it
                if (ProductionLogic.held(level, drain).isEmpty()) {
                    ItemStack moved = ProductionLogic.lift(level, depot);
                    if (!ProductionLogic.put(level, drain, moved)) ProductionLogic.put(level, depot, moved);
                }
            } else if (!result && stage(r, on)) {
                handOver(level, c, r, basin, on, deployerPos);
                haunt(level, depotPos, r, on);
            } else if (result || anyResult(c.def(), on)) {
                // the result (of this recipe, or of the one before the filter changed): out it goes
                if (!ProductionLogic.outputHasRoom(c, on)) {
                    c.problem = "siftec.building.full";
                    return;
                }
                ItemStack done = ProductionLogic.lift(level, depot);
                c.made += result ? Math.max(1, done.getCount() / Math.max(1, r.outCount())) : 1;
                ProductionLogic.deliver(c, done);
                c.setChanged();
            } else if (cellBearing(on)) {
                // a loaded cell of the recipe before (the filter changed): the cell goes back, what it was loaded with is spent
                ProductionLogic.lift(level, depot);
                if (c.carrier.isEmpty()) c.carrier = new ItemStack(cell);
                c.setChanged();
            } else {
                // a Core or half-done item of the recipe before: back into the Basin, never out as a result
                ItemStack back = ProductionLogic.lift(level, depot);
                ItemStack left = returnToBasin(basin, back);
                if (!left.isEmpty()) {
                    ProductionLogic.put(level, depot, left);
                    c.problem = "siftec.building.full";
                    return;
                }
                c.setChanged();
            }
        }
        if (!c.powered) {
            c.problem = "siftec.building.stopped";
            return;
        }
        c.problem = "";
        if (!ProductionLogic.held(level, depot).isEmpty()) return;
        // the Depot is free: the next Core the Mixer made, or for a recipe with none the cell, once each recipe time
        if (r.core() != null) {
            Item core = ProcessorRecipe.item(r.core());
            ItemStack next = ProductionLogic.takeFromBasin(basin, new Milestone.Cost(BuiltInRegistries.ITEM.getKey(core).toString(), 1), true);
            if (!next.isEmpty() && !ProductionLogic.put(level, depot, next)) ProductionLogic.giveBack(basin, next);
        } else if (c.carrier.is(cell) && level.getGameTime() >= c.nextCraft) {
            if (ProductionLogic.put(level, depot, c.carrier.copyWithCount(1))) {
                c.carrier = ItemStack.EMPTY;
                c.nextCraft = level.getGameTime() + r.seconds() * 20L;
                c.setChanged();
            }
        }
    }

    /** Ticks a Core may fly in the Particle Accelerator's column before it counts as stuck and goes back to the Basin. */
    static final int STUCK_TICKS = 200;

    /** True if one cycle's results fit into the output slot now. */
    static boolean resultFits(ControllerBlockEntity c, ProcessorRecipe r) {
        Item out = ProcessorRecipe.item(r.outItem());
        if (out == net.minecraft.world.item.Items.AIR) return true;
        ItemStack slot = c.output.getItem(0);
        int need = r.outPerCycle();
        if (slot.isEmpty()) return need <= out.getDefaultMaxStackSize();
        return slot.is(out) && slot.getCount() + need <= slot.getMaxStackSize();
    }

    /** Puts a whole stack back into the Basin's input slots as far as it goes; returns what did not fit. */
    static ItemStack returnToBasin(BasinBlockEntity basin, ItemStack stack) {
        return returnToBasin(basin, stack, false);
    }

    /** Puts a whole stack back into the Basin's input (0 to 8) or output (9 to 17) slots as far as it goes; returns what did not fit. */
    static ItemStack returnToBasin(BasinBlockEntity basin, ItemStack stack, boolean output) {
        Container inv = basin.itemCapability;
        ItemStack left = stack.copy();
        int from = output ? 9 : 0, to = output ? Math.min(18, inv.getContainerSize()) : 9;
        for (int pass = 0; pass < 2 && !left.isEmpty(); pass++) {
            for (int i = from; i < to && !left.isEmpty(); i++) {
                ItemStack there = inv.getItem(i);
                if (pass == 0 && !there.isEmpty() && ItemStack.isSameItemSameComponents(there, left)) {
                    int move = Math.min(left.getCount(), there.getMaxStackSize() - there.getCount());
                    if (move <= 0) continue;
                    there.grow(move);
                    left.shrink(move);
                } else if (pass == 1 && there.isEmpty()) {
                    inv.setItem(i, left.copy());
                    left = ItemStack.EMPTY;
                }
            }
        }
        inv.setChanged();
        return left;
    }

    /** A charged Converter Cell, of any fluid. */
    static boolean chargedCell(ItemStack stack) {
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace().equals("siftec") && id.startsWith("charged_") && id.endsWith("_cell");
    }

    /** True if the item is one of the recipe's steps on the way: its Core, a half-done one, or what the fan haunts. */
    private static boolean stage(ProcessorRecipe r, ItemStack on) {
        if (r.core() != null && on.is(ProcessorRecipe.item(r.core()))) return true;
        if (r.haunt() != null && on.is(ProcessorRecipe.item(r.haunt()))) return true;
        for (ProcessorRecipe.Step s : r.steps()) if (on.is(ProcessorRecipe.item(s.on())) || on.is(ProcessorRecipe.item(s.makes()))) return true;
        return false;
    }

    /** The Deployer gets the one item the step for what lies on the Depot puts on it: from the Basin, or the cell. */
    private static void handOver(ServerLevel level, ControllerBlockEntity c, ProcessorRecipe r, BasinBlockEntity basin, ItemStack on, BlockPos deployerPos) {
        if (!(level.getBlockEntity(deployerPos) instanceof DeployerBlockEntity deployer) || !deployer.heldItem.isEmpty()) return;
        for (ProcessorRecipe.Step s : r.steps()) {
            if (!on.is(ProcessorRecipe.item(s.on()))) continue;
            ItemStack one;
            boolean fromCell = s.apply().matches(new ItemStack(cellItem()));
            if (fromCell) {
                if (!c.carrier.is(cellItem())) return;
                one = c.carrier.copyWithCount(1);
            } else {
                one = ProductionLogic.takeFromBasin(basin, s.apply(), false);
                if (one.isEmpty()) return;
            }
            Storage<ItemVariant> hand = ItemStorage.SIDED.find(level, deployerPos, null);
            long put = 0;
            if (hand != null) {
                try (Transaction tx = Transaction.openOuter()) {
                    put = hand.insert(ItemVariant.of(one), 1, tx);
                    if (put == 1) tx.commit();
                }
            }
            if (put == 1 && fromCell) c.carrier = ItemStack.EMPTY;
            else if (put != 1 && !fromCell) ProductionLogic.giveBack(basin, one);
            c.setChanged();
            return;
        }
    }

    /** The fan's haunting takes a quarter of the recipe's time, so the line keeps up with the Mixer's pace. */
    private static void haunt(ServerLevel level, BlockPos depotPos, ProcessorRecipe r, ItemStack on) {
        if (r.haunt() == null || !on.is(ProcessorRecipe.item(r.haunt()))) return;
        if (!(level.getBlockEntity(depotPos) instanceof DepotBlockEntity d) || d.getHeldItem() == null) return;
        TransportedItemStack held = d.getHeldItem();
        // the Deployer's result keeps what the fan found for the item before it (the Core: nothing to haunt), so the
        // fan would never look again: it looks at the new item afresh
        if (held.processedBy == AllFanProcessingTypes.HAUNTING && held.processingTime < 0) {
            held.processedBy = null;
            held.processingTime = 0;
        }
        int most = Math.max(1, r.seconds() * 20 / 4);
        if (held.processedBy == AllFanProcessingTypes.HAUNTING && held.processingTime > most) held.processingTime = most;
    }

    // ------------------------------------------------------------------------------------------------ placing

    /**
     * How many Converter cells, in any state ({@link #cellBearing}), are anywhere in the building: its carrier, on the
     * Depot or in its output buffer, in the Item Drain, in the Deployer's hand, in the Basin, in the controller's slots or
     * lying loose in the building.
     */
    static int cellsIn(ServerLevel level, ControllerBlockEntity c) {
        int n = cellBearing(c.carrier) ? c.carrier.getCount() : 0;
        BlockPos depot = BuildingLogic.cell(c, create("depot")), drain = BuildingLogic.cell(c, create("item_drain")), deployer = BuildingLogic.cell(c, create("deployer"));
        if (depot != null) {
            ItemStack on = ProductionLogic.held(level, new ProductionLogic.Station(depot, "depot", null));
            if (cellBearing(on)) n += on.getCount();
            if (level.getBlockEntity(depot) instanceof DepotBlockEntity d) {
                Container buffer = d.depotBehaviour.processingOutputBuffer;
                for (int i = 0; i < buffer.getContainerSize(); i++) if (cellBearing(buffer.getItem(i))) n += buffer.getItem(i).getCount();
            }
        }
        if (drain != null) {
            ItemStack in = ProductionLogic.held(level, new ProductionLogic.Station(drain, "drain", null));
            if (cellBearing(in)) n += in.getCount();
        }
        if (deployer != null && level.getBlockEntity(deployer) instanceof DeployerBlockEntity d && cellBearing(d.heldItem)) n += d.heldItem.getCount();
        BasinBlockEntity basin = ProductionLogic.basin(level, c);
        if (basin != null) {
            Container inv = basin.itemCapability;
            for (int i = 0; i < inv.getContainerSize(); i++) if (cellBearing(inv.getItem(i))) n += inv.getItem(i).getCount();
        }
        for (net.minecraft.world.SimpleContainer slots : new net.minecraft.world.SimpleContainer[]{c.input, c.output, c.buffer}) {
            for (int i = 0; i < slots.getContainerSize(); i++) if (cellBearing(slots.getItem(i))) n += slots.getItem(i).getCount();
        }
        Placement p = c.placement();
        BuildingTemplate t = p == null ? null : BuildingTemplate.of(p.def());
        if (t != null) {
            for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, p.bounds(t))) if (cellBearing(e.getItem())) n += e.getItem().getCount();
        }
        return n;
    }

    /** A Converter that has no cell anywhere in it ({@link #cellsIn}: just built, or the recipe changed) gets the one that belongs to its machine. */
    static void ensureCell(ServerLevel level, ControllerBlockEntity c) {
        BuildingDef def = c.def();
        if (def == null || !def.id().equals("converter")) return;
        if (cellsIn(level, c) > 0) return;
        c.carrier = new ItemStack(cellItem());
        c.setChanged();
        Siftec.LOGGER.info("SIFTEC: building converter at {}: its Converter Cell was missing; it has one again", c.getBlockPos().toShortString());
    }
}
