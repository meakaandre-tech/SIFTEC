package com.meakaandre.siftec.backpack;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** A backpack slot. It only shows, and only takes items, once enough rewards have been earned. */
public class BackpackSlot extends Slot {
    /** Where the first backpack slot sits in the inventory window, just under the hotbar. */
    public static final int LEFT = 8, TOP = 170;
    /** Set by the client while the creative inventory is open, where these slots would sit on top of the hotbar. */
    public static volatile boolean hiddenOnClient;
    private final Player player;
    private final int number;

    public BackpackSlot(Player player, Container container, int number) {
        super(container, number, LEFT + (number % 9) * 18, TOP + (number / 9) * 18);
        this.player = player;
        this.number = number;
    }

    private boolean open() {
        return number < Backpack.unlocked(player);
    }

    @Override
    public boolean isActive() {
        if (hiddenOnClient && player.level().isClientSide()) return false;
        return open() || hasItem();
    }

    @Override
    public boolean mayPlace(ItemStack stack) {
        return open();
    }
}
