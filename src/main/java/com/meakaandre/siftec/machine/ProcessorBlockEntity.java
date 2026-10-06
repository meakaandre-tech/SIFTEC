package com.meakaandre.siftec.machine;

import com.meakaandre.siftec.hub.Milestone;
import com.zurrtum.create.api.behaviour.BlockEntityBehaviour;
import com.zurrtum.create.content.kinetics.base.KineticBlockEntity;
import com.zurrtum.create.foundation.blockEntity.behaviour.fluid.SmartFluidTankBehaviour;
import com.zurrtum.create.infrastructure.fluids.FluidStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.Containers;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

import java.util.List;

public class ProcessorBlockEntity extends KineticBlockEntity {
    private static final int MB = 81, INPUTS = 9, OUTPUT = 9;

    public final Items items = new Items();
    public SmartFluidTankBehaviour tank;
    private int selected;
    private float progress;

    public ProcessorBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour<?>> behaviours) {
        super.addBehaviours(behaviours);
        tank = SmartFluidTankBehaviour.single(this, 4000 * MB);
        behaviours.add(tank);
    }

    private List<ProcessorRecipe> recipes() {
        return getBlockState().getBlock() instanceof ProcessorBlock block ? ProcessorRecipe.of(block.machine) : List.of();
    }

    private @Nullable ProcessorRecipe recipe() {
        List<ProcessorRecipe> list = recipes();
        return list.isEmpty() ? null : list.get(Math.floorMod(selected, list.size()));
    }

    /** An empty-handed click: take the output, or (sneaking) switch to the next recipe. */
    public void use(Player player) {
        if (!(player instanceof ServerPlayer server)) return;
        ItemStack out = items.stacks.get(OUTPUT);
        if (!player.isShiftKeyDown() && !out.isEmpty()) {
            items.stacks.set(OUTPUT, ItemStack.EMPTY);
            player.getInventory().placeItemBackInInventory(out, Prediction.SERVER_ONLY);
            setChanged();
            return;
        }
        if (player.isShiftKeyDown() && !recipes().isEmpty()) {
            selected = Math.floorMod(selected + 1, recipes().size());
            progress = 0;
            // what was waiting for the old recipe comes back out
            for (int i = 0; i < INPUTS; i++) {
                ItemStack stack = items.stacks.get(i);
                if (stack.isEmpty()) continue;
                items.stacks.set(i, ItemStack.EMPTY);
                player.getInventory().placeItemBackInInventory(stack, Prediction.SERVER_ONLY);
            }
            setChanged();
        }
        ProcessorRecipe recipe = recipe();
        if (recipe != null) server.sendOverlayMessage(Component.translatable("siftec.processor.selected", recipe.label()));
    }

    /** Puts a held stack into the inputs if the selected recipe uses it. */
    public boolean insert(ItemStack stack) {
        ProcessorRecipe recipe = recipe();
        if (recipe == null || !recipe.uses(stack)) return false;
        for (int i = 0; i < INPUTS && !stack.isEmpty(); i++) {
            ItemStack slot = items.stacks.get(i);
            if (slot.isEmpty()) {
                items.stacks.set(i, stack.copy());
                stack.setCount(0);
            } else if (ItemStack.isSameItemSameComponents(slot, stack)) {
                int move = Math.min(stack.getCount(), slot.getMaxStackSize() - slot.getCount());
                slot.grow(move);
                stack.shrink(move);
            }
        }
        setChanged();
        return true;
    }

    private int have(Milestone.Cost cost) {
        int n = 0;
        for (int i = 0; i < INPUTS; i++) if (cost.matches(items.stacks.get(i))) n += items.stacks.get(i).getCount();
        return n;
    }

    private boolean ready(ProcessorRecipe recipe) {
        for (Milestone.Cost cost : recipe.inputs()) if (have(cost) < cost.count()) return false;
        FluidStack held = tank.getPrimaryHandler().getFluid();
        if (recipe.fluidIn() != null) {
            Fluid needed = ProcessorRecipe.fluid(recipe.fluidIn());
            if (needed == Fluids.EMPTY || held.isEmpty() || held.getFluid() != needed || held.getAmount() < recipe.fluidInMb() * MB) return false;
        }
        if (recipe.outItem() != null) {
            ItemStack out = items.stacks.get(OUTPUT);
            if (recipe.item() == net.minecraft.world.item.Items.AIR) return false;
            if (!out.isEmpty() && (!out.is(recipe.item()) || out.getCount() + recipe.outCount() > out.getMaxStackSize())) return false;
        }
        if (recipe.fluidOut() != null) {
            Fluid made = ProcessorRecipe.fluid(recipe.fluidOut());
            if (made == Fluids.EMPTY) return false;
            if (!held.isEmpty() && (held.getFluid() != made || held.getAmount() + recipe.fluidOutMb() * MB > 4000 * MB)) return false;
        }
        return true;
    }

    @Override
    public void tick() {
        super.tick();
        if (level == null || level.isClientSide()) return;
        ProcessorRecipe recipe = recipe();
        float speed = Math.abs(getSpeed());
        if (recipe == null || speed == 0 || !ready(recipe)) {
            progress = 0;
            return;
        }
        // a recipe's time is what it takes at 32 RPM
        progress += speed / 32f;
        if (progress < recipe.seconds() * 20f) return;
        progress = 0;
        for (Milestone.Cost cost : recipe.inputs()) {
            int left = cost.count();
            for (int i = 0; i < INPUTS && left > 0; i++) {
                ItemStack stack = items.stacks.get(i);
                if (!cost.matches(stack)) continue;
                int take = Math.min(left, stack.getCount());
                stack.shrink(take);
                if (stack.isEmpty()) items.stacks.set(i, ItemStack.EMPTY);
                left -= take;
            }
        }
        if (recipe.fluidIn() != null) tank.getPrimaryHandler().extract(tank.getPrimaryHandler().getFluid(), recipe.fluidInMb() * MB);
        if (recipe.outItem() != null) {
            ItemStack out = items.stacks.get(OUTPUT);
            if (out.isEmpty()) items.stacks.set(OUTPUT, new ItemStack(recipe.item(), recipe.outCount()));
            else out.grow(recipe.outCount());
        }
        if (recipe.fluidOut() != null) tank.getPrimaryHandler().insert(new FluidStack(ProcessorRecipe.fluid(recipe.fluidOut()), recipe.fluidOutMb() * MB));
        setChanged();
    }

    @Override
    public float calculateStressApplied() {
        lastStressApplied = 64f;
        return lastStressApplied;
    }

    @Override
    public void destroy() {
        super.destroy();
        if (level != null) Containers.dropContents(level, worldPosition, items);
    }

    @Override
    protected void write(ValueOutput view, boolean clientPacket) {
        super.write(view, clientPacket);
        view.putInt("Selected", selected);
        view.putFloat("Progress", progress);
        view.store("Items", ItemStack.OPTIONAL_CODEC.listOf(), List.copyOf(items.stacks));
    }

    @Override
    protected void read(ValueInput view, boolean clientPacket) {
        super.read(view, clientPacket);
        selected = view.getIntOr("Selected", 0);
        progress = view.getFloatOr("Progress", 0);
        List<ItemStack> saved = view.read("Items", ItemStack.OPTIONAL_CODEC.listOf()).orElse(List.of());
        for (int i = 0; i < items.stacks.size(); i++) items.stacks.set(i, i < saved.size() ? saved.get(i) : ItemStack.EMPTY);
    }

    /** Nine input slots, which only take what the selected recipe uses, and one output slot. */
    public class Items implements WorldlyContainer {
        private static final int[] SLOTS = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
        final NonNullList<ItemStack> stacks = NonNullList.withSize(10, ItemStack.EMPTY);

        @Override
        public int[] getSlotsForFace(Direction side) {
            return SLOTS;
        }

        @Override
        public boolean canPlaceItem(int slot, ItemStack stack) {
            ProcessorRecipe recipe = recipe();
            return slot < INPUTS && recipe != null && recipe.uses(stack);
        }

        @Override
        public boolean canPlaceItemThroughFace(int slot, ItemStack stack, @Nullable Direction side) {
            return canPlaceItem(slot, stack);
        }

        @Override
        public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction side) {
            return slot == OUTPUT;
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
