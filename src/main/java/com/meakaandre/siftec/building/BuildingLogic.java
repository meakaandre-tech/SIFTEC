package com.meakaandre.siftec.building;

import com.meakaandre.siftec.block.DepletedNodeBlock;
import com.meakaandre.siftec.block.NodeBlock;
import com.meakaandre.siftec.node.NodeLook;
import com.meakaandre.siftec.node.Purity;
import com.zurrtum.create.content.kinetics.base.KineticBlockEntity;
import com.zurrtum.create.content.kinetics.belt.transport.TransportedItemStack;
import com.zurrtum.create.content.kinetics.fan.EncasedFanBlock;
import com.zurrtum.create.content.kinetics.fan.processing.AllFanProcessingTypes;
import com.zurrtum.create.content.logistics.depot.DepotBlockEntity;
import com.zurrtum.create.content.processing.basin.BasinBlockEntity;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * How each kind of building works, run by its controller every tick ({@link BuildingDef#logic()}):
 * <ul>
 * <li>basin (the Constructor): a Press over a Basin. The controller's filter is the Basin's, so it picks the recipe;
 * {@link BasinPace} keeps the Press to Satisfactory's time per recipe. The ports work on the Basin.</li>
 * <li>smelter: an Encased Fan blowing through lava onto a Depot. The controller takes ore in at its input port, puts
 * it on the Depot a few at a time with Satisfactory's time per item, and takes the ingots off into its output.</li>
 * <li>miner: a Drill pointing down at the node. While the Drill turns, ore comes out at Satisfactory's rate for the
 * miner's Mk and the node's purity, into the controller's output, which the output port puts on a belt. The Drill
 * itself never breaks the node.</li>
 * <li>portable_miner: the same at the Portable Miner's rate, with no power, the ore dropping on the ground in front
 * (it stops while a full stack lies there).</li>
 * <li>storage: Item Vaults; nothing to run.</li>
 * <li>power: the generators, Power Storage, poles and towers ({@link BuildingPower}).</li>
 * </ul>
 * A powered building's New Age motors run while its grid has power ({@link BuildingPower}); "stopped" in its status
 * means they do not.
 */
public final class BuildingLogic {
    /** The speed a building's motor turns its machines at. */
    public static final float MOTOR_RPM = 64;
    /** Items the Smelter puts on its Depot at once (the Depot holds one stack; a few at a time keeps the gaps short). */
    static final int SMELTER_BATCH = 4;
    /** Ticks in a minute: rates are items a minute. */
    private static final float MINUTE = 1200f;

    private BuildingLogic() {
    }

    // ------------------------------------------------------------------------------------------------ the cells

    /** Where the building's blocks of one kind stand in the world. */
    public static List<BlockPos> cells(@Nullable ControllerBlockEntity c, Block block) {
        List<BlockPos> out = new ArrayList<>();
        Placement p = c == null ? null : c.placement();
        BuildingTemplate t = p == null ? null : BuildingTemplate.of(p.def());
        if (t == null) return out;
        for (BuildingTemplate.Cell cell : t.members) if (cell.state().getBlock() == block) out.add(p.at(cell));
        return out;
    }

    public static @Nullable BlockPos cell(@Nullable ControllerBlockEntity c, Block block) {
        List<BlockPos> all = cells(c, block);
        return all.isEmpty() ? null : all.getFirst();
    }

    private static Block create(String id) {
        return BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.fromNamespaceAndPath("create", id));
    }

    // ------------------------------------------------------------------------------------------------ placing

    /**
     * Called once the building has gone down: its New Age motors are set to the speed its machines turn at (a fan's
     * the way that blows away from it), and its Basin's filter is the controller's. The motors only turn while the
     * building's grid gives them power ({@link BuildingPower}).
     */
    static void placed(ServerLevel level, ControllerBlockEntity c) {
        BuildingDef def = c.def();
        if (def == null) return;
        for (BlockPos pos : BuildingPower.motors(c)) {
            if (!(level.getBlockEntity(pos) instanceof org.antarcticgardens.cna.content.motor.MotorBlockEntity motor)) continue;
            int rpm = (int) MOTOR_RPM;
            // a fan turned the wrong way would suck instead of blow: the motor turns so it blows away from it
            Direction toward = motor.getBlockState().getValue(com.zurrtum.create.content.kinetics.base.DirectionalKineticBlock.FACING);
            BlockState next = level.getBlockState(pos.relative(toward));
            if (next.getBlock() instanceof EncasedFanBlock) rpm *= next.getValue(EncasedFanBlock.FACING).getAxisDirection().getStep();
            if (motor.speedBehavior != null) motor.speedBehavior.setValue(rpm);
            motor.setChanged();
        }
        syncFilter(level, c);
    }

    /** The Basin's filter follows the controller's. */
    static void syncFilter(Level level, ControllerBlockEntity c) {
        for (BlockPos pos : cells(c, create("basin"))) {
            if (level.getBlockEntity(pos) instanceof BasinBlockEntity basin && !ItemStack.matches(basin.getFilter().getFilter(), c.filter)) {
                basin.getFilter().setFilter(c.filter.copy());
            }
        }
    }

    /** Sets what the building makes (empty: anything). False if it has no recipe filter. */
    public static boolean setFilter(Level level, ControllerBlockEntity c, ItemStack stack) {
        BuildingDef def = c.def();
        if (def == null || !def.logic().equals("basin")) return false;
        c.filter = stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1);
        c.setChanged();
        syncFilter(level, c);
        return true;
    }

    // ------------------------------------------------------------------------------------------------ running

    static void tick(ServerLevel level, ControllerBlockEntity c) {
        BuildingDef def = c.def();
        if (def == null) return;
        switch (def.logic()) {
            case "miner" -> mine(level, c, def, true);
            case "portable_miner" -> mine(level, c, def, false);
            case "smelter" -> smelt(level, c);
            default -> {
            }
        }
    }

    /** What the input port may put into the controller: the Smelter takes the ores it smelts; nothing else takes anything. */
    static boolean accepts(ControllerBlockEntity c, ItemStack stack) {
        BuildingDef def = c.def();
        if (def == null) return false;
        if (def.logic().equals("smelter")) return MachineTimes.smelterTicks(stack) > 0;
        return BuildingPower.acceptsFuel(def, stack);
    }

    private static boolean turning(Level level, @Nullable BlockPos machine) {
        return machine != null && level.getBlockEntity(machine) instanceof KineticBlockEntity k && k.getSpeed() != 0;
    }

    /** The node's look and purity, or null if no node stands in the node cell any more. */
    private record NodeAt(NodeLook look, Purity purity) {
    }

    private static @Nullable NodeAt nodeAt(Level level, @Nullable BlockPos pos) {
        if (pos == null || !level.isLoaded(pos)) return null;
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof NodeBlock node && !node.look.fluid()) return new NodeAt(node.look, state.getValue(NodeBlock.PURITY));
        if (state.getBlock() instanceof DepletedNodeBlock depleted) return new NodeAt(depleted.look, state.getValue(NodeBlock.PURITY));
        return null;
    }

    /** Items a minute this miner gets from its node right now (0 without a node). */
    public static float rate(Level level, ControllerBlockEntity c) {
        BuildingDef def = c.def();
        NodeAt node = nodeAt(level, c.node);
        return def == null || node == null ? 0 : def.rate() * node.purity().multiplier;
    }

    private static void mine(ServerLevel level, ControllerBlockEntity c, BuildingDef def, boolean powered) {
        NodeAt node = nodeAt(level, c.node);
        if (node == null) {
            c.problem = "siftec.building.no_node";
            return;
        }
        if (powered && !turning(level, cell(c, create("mechanical_drill")))) {
            c.problem = "siftec.building.stopped";
            return;
        }
        Item item = node.look().output();
        if (item == Items.AIR) return;
        ItemStack one = new ItemStack(item);
        if (powered ? !fits(c.output.getItem(0), one) : groundFull(level, c, item)) {
            c.problem = "siftec.building.full";
            return;
        }
        c.problem = "";
        c.progress += def.rate() * node.purity().multiplier;
        if (c.progress < MINUTE) return;
        c.progress -= MINUTE;
        c.made++;
        if (powered) {
            ItemStack out = c.output.getItem(0);
            if (out.isEmpty()) c.output.setItem(0, one);
            else out.grow(1);
            c.output.setChanged();
        } else {
            BlockPos at = dropSpot(c);
            if (at == null) return;
            ItemEntity entity = new ItemEntity(level, at.getX() + 0.5, at.getY() + 0.1, at.getZ() + 0.5, one);
            entity.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            level.addFreshEntity(entity);
        }
        c.setChanged();
    }

    private static boolean fits(ItemStack slot, ItemStack one) {
        return slot.isEmpty() || ItemStack.isSameItemSameComponents(slot, one) && slot.getCount() < slot.getMaxStackSize();
    }

    /** Where a Portable Miner drops its ore: on the ground just in front of it. */
    public static @Nullable BlockPos dropSpot(ControllerBlockEntity c) {
        Placement p = c.placement();
        return p == null ? null : p.at(0, 0, -1);
    }

    /** True while a full stack of the ore lies where the Portable Miner drops it. */
    private static boolean groundFull(Level level, ControllerBlockEntity c, Item item) {
        BlockPos at = dropSpot(c);
        if (at == null) return true;
        int n = 0;
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, new AABB(at).inflate(1))) if (e.getItem().is(item)) n += e.getItem().getCount();
        return n >= item.getDefaultMaxStackSize();
    }

    private static void smelt(ServerLevel level, ControllerBlockEntity c) {
        BlockPos depotPos = cell(c, create("depot"));
        if (depotPos == null || !(level.getBlockEntity(depotPos) instanceof DepotBlockEntity depot)) return;
        TransportedItemStack held = depot.getHeldItem();
        // a finished batch (what lies there no longer smelts here) goes to the output, once it all fits
        if (held != null && !held.stack.isEmpty() && MachineTimes.smelterTicks(held.stack) == 0) {
            ItemStack out = c.output.getItem(0);
            if (!out.isEmpty() && (!ItemStack.isSameItemSameComponents(out, held.stack) || out.getCount() + held.stack.getCount() > out.getMaxStackSize())) {
                c.problem = "siftec.building.full";
                return;
            }
            c.made += held.stack.getCount();
            if (out.isEmpty()) c.output.setItem(0, held.stack.copy());
            else out.grow(held.stack.getCount());
            c.output.setChanged();
            depot.depotBehaviour.removeHeldItem();
            depot.notifyUpdate();
            held = null;
        }
        boolean fan = turning(level, cell(c, create("encased_fan")));
        c.problem = fan ? "" : "siftec.building.stopped";
        if (held != null && !held.stack.isEmpty() || !fan) return;
        // the Depot is free: the next few ores go on, each to take Satisfactory's time under the lava's blast
        ItemStack in = c.input.getItem(0);
        int per = MachineTimes.smelterTicks(in);
        if (in.isEmpty() || per <= 0) return;
        ItemStack batch = in.split(Math.min(SMELTER_BATCH, in.getCount()));
        c.input.setChanged();
        TransportedItemStack put = new TransportedItemStack(batch);
        put.processedBy = AllFanProcessingTypes.BLASTING;
        put.processingTime = per * batch.getCount();
        depot.depotBehaviour.setCenteredHeldItem(put);
        depot.notifyUpdate();
        c.setChanged();
    }

    // ------------------------------------------------------------------------------------------------ status

    /** What the controller says when it is used: the building's name, what it makes or mines, and anything wrong. */
    public static Component status(Level level, ControllerBlockEntity c) {
        BuildingDef def = c.def();
        if (def == null) return Component.translatable("siftec.building.unknown", c.buildingId);
        List<Component> parts = new ArrayList<>();
        switch (def.logic()) {
            case "basin" -> parts.add(c.filter.isEmpty() ? Component.translatable("siftec.building.no_filter")
                : Component.translatable("siftec.building.filter", c.filter.getHoverName()));
            case "miner", "portable_miner" -> {
                NodeAt node = nodeAt(level, c.node);
                parts.add(node == null ? Component.translatable("siftec.building.no_node")
                    : Component.translatable("siftec.building.node", NodeBlock.label(node.look().type(), node.purity()), formatRate(rate(level, c))));
            }
            case "storage" -> {
                int used = 0, total = 0;
                BlockPos vault = cell(c, create("item_vault"));
                Storage<ItemVariant> s = vault == null ? null : ItemStorage.SIDED.find(level, vault, null);
                if (s != null) {
                    for (StorageView<ItemVariant> v : s) {
                        total++;
                        if (!v.isResourceBlank()) used++;
                    }
                }
                parts.add(Component.translatable("siftec.building.storage", used, total));
            }
            default -> {
            }
        }
        if (!def.logic().equals("storage") && !def.logic().isEmpty() && !def.logic().equals("power")) parts.add(Component.translatable("siftec.building.made", c.made));
        BuildingPower.status(level, c, def, parts);
        if (!c.problem.isEmpty()) parts.add(Component.translatable(c.problem));
        Component line = Component.empty();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) line = line.copy().append(", ");
            line = line.copy().append(parts.get(i));
        }
        return Component.translatable("siftec.building.status", def.name(), line);
    }

    private static String formatRate(float perMinute) {
        return perMinute == Math.rint(perMinute) ? String.valueOf((int) perMinute) : String.valueOf(perMinute);
    }
}
