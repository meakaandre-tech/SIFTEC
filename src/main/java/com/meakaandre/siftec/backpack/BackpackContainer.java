package com.meakaandre.siftec.backpack;

import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** The backpack's slots as a container. It looks the contents up each time, because they load after the player is made. */
public class BackpackContainer implements Container {
    private final Player player;

    public BackpackContainer(Player player) {
        this.player = player;
    }

    private Backpack.Contents contents() {
        return player.getAttachedOrCreate(Backpack.CONTENTS);
    }

    @Override
    public int getContainerSize() {
        return Backpack.SIZE;
    }

    @Override
    public boolean isEmpty() {
        for (ItemStack stack : contents().items) if (!stack.isEmpty()) return false;
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        return contents().items.get(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        return ContainerHelper.removeItem(contents().items, slot, amount);
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return ContainerHelper.takeItem(contents().items, slot);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        contents().items.set(slot, stack);
    }

    @Override
    public void setChanged() {
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public void clearContent() {
        contents().items.clear();
    }
}
