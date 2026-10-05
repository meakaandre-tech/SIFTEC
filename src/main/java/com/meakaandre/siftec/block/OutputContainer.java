package com.meakaandre.siftec.block;

import net.minecraft.core.Direction;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/** One output slot: machines and players can take from it, nothing can be put in from outside. */
public class OutputContainer implements WorldlyContainer {
    private static final int[] SLOTS = {0};
    private final Runnable changed;
    private ItemStack stack = ItemStack.EMPTY;

    public OutputContainer(Runnable changed) {
        this.changed = changed;
    }

    public ItemStack get() {
        return stack;
    }

    public void set(ItemStack stack) {
        this.stack = stack;
    }

    @Override
    public int[] getSlotsForFace(Direction side) {
        return SLOTS;
    }

    @Override
    public boolean canPlaceItemThroughFace(int slot, ItemStack stack, @Nullable Direction side) {
        return false;
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return false;
    }

    @Override
    public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction side) {
        return true;
    }

    @Override
    public int getContainerSize() {
        return 1;
    }

    @Override
    public boolean isEmpty() {
        return stack.isEmpty();
    }

    @Override
    public ItemStack getItem(int slot) {
        return slot == 0 ? stack : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        if (slot != 0 || stack.isEmpty() || amount <= 0) return ItemStack.EMPTY;
        ItemStack taken = stack.split(amount);
        if (stack.isEmpty()) stack = ItemStack.EMPTY;
        setChanged();
        return taken;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        if (slot != 0) return ItemStack.EMPTY;
        ItemStack taken = stack;
        stack = ItemStack.EMPTY;
        return taken;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (slot == 0) this.stack = stack;
    }

    @Override
    public void setChanged() {
        changed.run();
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public void clearContent() {
        stack = ItemStack.EMPTY;
    }
}
