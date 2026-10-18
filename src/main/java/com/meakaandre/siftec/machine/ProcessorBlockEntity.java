package com.meakaandre.siftec.machine;

import com.zurrtum.create.api.behaviour.BlockEntityBehaviour;
import com.zurrtum.create.content.kinetics.base.KineticBlockEntity;
import com.zurrtum.create.foundation.blockEntity.behaviour.fluid.SmartFluidTankBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The old single-block Converter or Particle Accelerator, as an old world still has it. It makes nothing any more
 * (the Build Gun buildings of the same names run their recipes on real machines): it only keeps what it held, and the
 * first time it ticks it is turned into the building, or taken away with its cost given back
 * ({@link com.meakaandre.siftec.building.LegacyConversion}).
 */
public class ProcessorBlockEntity extends KineticBlockEntity {
    private static final int MB = 81;

    public final Items items = new Items();
    public SmartFluidTankBehaviour tank;
    private boolean queued;

    public ProcessorBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour<?>> behaviours) {
        super.addBehaviours(behaviours);
        tank = SmartFluidTankBehaviour.single(this, 4000 * MB);
        behaviours.add(tank);
    }

    /** Which building this was: "converter" or "particle_accelerator". */
    public String machine() {
        return getBlockState().getBlock() instanceof ProcessorBlock block ? block.machine : "";
    }

    @Override
    public void tick() {
        super.tick();
        if (queued || !(level instanceof ServerLevel server)) return;
        queued = true;
        com.meakaandre.siftec.building.LegacyConversion.queue(server, worldPosition);
    }

    /** Everything it held, emptied out of it. */
    public List<ItemStack> takeAll() {
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < items.stacks.size(); i++) {
            ItemStack stack = items.stacks.get(i);
            if (!stack.isEmpty()) out.add(stack);
            items.stacks.set(i, ItemStack.EMPTY);
        }
        return out;
    }

    @Override
    public float calculateStressApplied() {
        lastStressApplied = 0f;
        return lastStressApplied;
    }

    @Override
    protected void write(ValueOutput view, boolean clientPacket) {
        super.write(view, clientPacket);
        view.store("Items", ItemStack.OPTIONAL_CODEC.listOf(), List.copyOf(items.stacks));
    }

    @Override
    protected void read(ValueInput view, boolean clientPacket) {
        super.read(view, clientPacket);
        List<ItemStack> saved = view.read("Items", ItemStack.OPTIONAL_CODEC.listOf()).orElse(List.of());
        for (int i = 0; i < items.stacks.size(); i++) items.stacks.set(i, i < saved.size() ? saved.get(i) : ItemStack.EMPTY);
    }

    /** What it held: nothing goes in any more, and everything comes out. */
    public class Items implements WorldlyContainer {
        private static final int[] SLOTS = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
        public final NonNullList<ItemStack> stacks = NonNullList.withSize(10, ItemStack.EMPTY);

        @Override
        public int[] getSlotsForFace(Direction side) {
            return SLOTS;
        }

        @Override
        public boolean canPlaceItem(int slot, ItemStack stack) {
            return false;
        }

        @Override
        public boolean canPlaceItemThroughFace(int slot, ItemStack stack, @Nullable Direction side) {
            return false;
        }

        @Override
        public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction side) {
            return true;
        }

        @Override
        public int getContainerSize() {
            return stacks.size();
        }

        @Override
        public boolean isEmpty() {
            for (ItemStack stack : stacks) if (!stack.isEmpty()) return false;
            return true;
        }

        @Override
        public ItemStack getItem(int slot) {
            return stacks.get(slot);
        }

        @Override
        public ItemStack removeItem(int slot, int amount) {
            ItemStack taken = ContainerHelper.removeItem(stacks, slot, amount);
            if (!taken.isEmpty()) setChanged();
            return taken;
        }

        @Override
        public ItemStack removeItemNoUpdate(int slot) {
            return ContainerHelper.takeItem(stacks, slot);
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
            stacks.set(slot, stack);
        }

        @Override
        public void setChanged() {
            ProcessorBlockEntity.this.setChanged();
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }

        @Override
        public void clearContent() {
            stacks.clear();
        }
    }
}
