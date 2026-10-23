package com.meakaandre.siftec.building;

import com.zurrtum.create.api.behaviour.BlockEntityBehaviour;
import com.zurrtum.create.content.kinetics.belt.behaviour.DirectBeltInputBehaviour;
import com.zurrtum.create.content.kinetics.belt.transport.TransportedItemStack;
import com.zurrtum.create.content.processing.basin.BasinBlockEntity;
import com.zurrtum.create.foundation.blockEntity.SmartBlockEntity;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.item.ContainerStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageUtil;
import net.fabricmc.fabric.api.transfer.v1.storage.base.CombinedStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.base.FilteringStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A port in the world: the cell inside the building it feeds or drains ({@code target}) and its controller. Only its
 * outward face takes or gives anything, and only the kind it is (items or fluids, in or out).
 * <p>
 * An item input takes items a belt carries into it (a belt ends at it as at a Depot); an item output pushes one item
 * a tick from its target onto a belt, Depot or chute in front of it, or into an inventory there.
 */
public class PortBlockEntity extends SmartBlockEntity {
    public @Nullable BlockPos target;
    public @Nullable BlockPos controller;
    /** Items pushed out of an output port, for the selftests. */
    public long pushed;

    public PortBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour<?>> behaviours) {
        behaviours.add(new DirectBeltInputBehaviour(this).onlyInsertWhen(this::beltCanInsert).setInsertionHandler(this::fromBelt));
    }

    private PortBlock.@Nullable Kind kind() {
        BlockState state = getBlockState();
        return state.hasProperty(PortBlock.KIND) ? state.getValue(PortBlock.KIND) : null;
    }

    private Direction out() {
        BlockState state = getBlockState();
        return state.hasProperty(PortBlock.FACING) ? state.getValue(PortBlock.FACING) : Direction.SOUTH;
    }

    /**
     * True while the building runs for real: its controller's chunk ticks and the factory simulation is not running it.
     * A port in a chunk that ticks while its controller's does not (a building across the edge of simulation distance)
     * passes nothing, or it and the simulation would both move the same things.
     */
    private boolean live() {
        if (level == null || controller == null || level.isClientSide()) return true;
        if (!level.isLoaded(controller) || !level.shouldTickBlocksAt(controller)) return false;
        return !(level.getBlockEntity(controller) instanceof ControllerBlockEntity c) || !c.simulated;
    }

    private boolean serves(@Nullable Direction side, boolean fluid) {
        BlockState state = getBlockState();
        if (level == null || target == null || !state.hasProperty(PortBlock.KIND)) return false;
        if (!live()) return false;
        if (state.getValue(PortBlock.KIND).fluid() != fluid) return false;
        if (side != null && side != state.getValue(PortBlock.FACING)) return false;
        // a port never passes things on to another port (no loops)
        return !(level.getBlockState(target).getBlock() instanceof PortBlock);
    }

    public @Nullable Storage<ItemVariant> items(@Nullable Direction side) {
        if (!serves(side, false)) return null;
        // a Basin lets its ingredients out as well as what it has made: an output port only takes what it has made
        if (kind() == PortBlock.Kind.ITEM_OUT && level.getBlockEntity(target) instanceof BasinBlockEntity basin) {
            var slots = ContainerStorage.of(basin.itemCapability, null).getSlots();
            return FilteringStorage.extractOnlyOf(new CombinedStorage<>(new ArrayList<>(slots.subList(9, Math.min(18, slots.size())))));
        }
        // the controller's slots (a miner's ore, the Smelter's ore and ingots, a generator's fuel) are reached only from
        // its ports: it registers no item storage of its own, so nothing beside it can take from or put into it
        Storage<ItemVariant> inner = level.getBlockEntity(target) instanceof ControllerBlockEntity controller ? controller.storage()
            : ItemStorage.SIDED.find(level, target, out());
        if (inner == null) return null;
        // the Particle Accelerator's and the Converter's input port lets in only what the current recipe takes, so junk
        // never jams the Basin
        if (kind() == PortBlock.Kind.ITEM_IN && controller != null && level.getBlockEntity(controller) instanceof ControllerBlockEntity c
            && c.def() != null && c.def().logic().equals("processor") && level.getBlockEntity(target) instanceof BasinBlockEntity) {
            return new FilteringStorage<>(inner) {
                @Override
                protected boolean canInsert(ItemVariant resource) {
                    return ProductionLogic.processorTakes(c, resource.toStack());
                }

                @Override
                protected boolean canExtract(ItemVariant resource) {
                    return false;
                }
            };
        }
        return kind() == PortBlock.Kind.ITEM_IN ? FilteringStorage.insertOnlyOf(inner) : FilteringStorage.extractOnlyOf(inner);
    }

    public @Nullable Storage<FluidVariant> fluids(@Nullable Direction side) {
        if (!serves(side, true)) return null;
        Storage<FluidVariant> inner = ProductionLogic.fluids(level, target, out());
        // a machine that takes fluid only from one side (Diesel Generators' engine, from below): the port reaches it there
        for (Direction d : Direction.values()) {
            if (inner != null) break;
            inner = ProductionLogic.fluids(level, target, d);
        }
        if (inner == null) return null;
        return kind() == PortBlock.Kind.FLUID_IN ? FilteringStorage.insertOnlyOf(inner) : FilteringStorage.extractOnlyOf(inner);
    }

    // ------------------------------------------------------------------------------------------------ belts

    /** A belt runs into an item input from outside: its items move against the way the port faces. */
    private boolean beltCanInsert(Direction side) {
        return kind() == PortBlock.Kind.ITEM_IN && side == out().getOpposite();
    }

    private ItemStack fromBelt(TransportedItemStack transported, Direction side, boolean simulate) {
        ItemStack stack = transported.stack;
        Storage<ItemVariant> into = beltCanInsert(side) ? items(out()) : null;
        if (into == null || stack.isEmpty()) return stack;
        long took;
        try (Transaction tx = Transaction.openOuter()) {
            took = into.insert(ItemVariant.of(stack), stack.getCount(), tx);
            if (!simulate) tx.commit();
        }
        if (took <= 0) return stack;
        return took >= stack.getCount() ? ItemStack.EMPTY : stack.copyWithCount(stack.getCount() - (int) took);
    }

    @Override
    public void tick() {
        super.tick();
        if (level == null || level.isClientSide() || kind() != PortBlock.Kind.ITEM_OUT) return;
        push();
    }

    /** Puts one item from the target onto the belt (or Depot, chute, inventory) in front, if it has room. */
    private void push() {
        Direction out = out();
        Storage<ItemVariant> from = items(out);
        if (from == null) return;
        // the first thing it can really take (a view it cannot take from, an input slot behind the same storage, is skipped)
        ItemVariant next;
        try (Transaction tx = Transaction.openOuter()) {
            next = StorageUtil.findExtractableResource(from, tx);
        }
        if (next == null || next.isBlank()) return;
        BlockPos front = worldPosition.relative(out);
        DirectBeltInputBehaviour belt = BlockEntityBehaviour.get(level, front, DirectBeltInputBehaviour.TYPE);
        if (belt != null) {
            if (!belt.canInsertFromSide(out)) return;
            ItemStack one = next.toStack(1);
            if (!belt.handleInsertion(one.copy(), out, true).isEmpty()) return;
            try (Transaction tx = Transaction.openOuter()) {
                if (from.extract(next, 1, tx) != 1) return;
                tx.commit();
            }
            belt.handleInsertion(one, out, false);
            pushed++;
            return;
        }
        Storage<ItemVariant> to = ItemStorage.SIDED.find(level, front, out.getOpposite());
        if (to == null) return;
        try (Transaction tx = Transaction.openOuter()) {
            if (from.extract(next, 1, tx) != 1) return;
            if (to.insert(next, 1, tx) != 1) return;
            tx.commit();
            pushed++;
        }
    }

    @Override
    protected void write(ValueOutput view, boolean clientPacket) {
        super.write(view, clientPacket);
        if (target != null) view.store("Feeds", BlockPos.CODEC, target);
        if (controller != null) view.store("Controller", BlockPos.CODEC, controller);
    }

    @Override
    protected void read(ValueInput view, boolean clientPacket) {
        super.read(view, clientPacket);
        target = view.read("Feeds", BlockPos.CODEC).orElse(null);
        controller = view.read("Controller", BlockPos.CODEC).orElse(null);
    }
}
