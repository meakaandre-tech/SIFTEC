package com.meakaandre.siftec.backpack;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * A backpack slot: one cell of the extra inventory rows. It only shows, and only takes items, once enough rewards
 * have been earned; a slot that is no longer open but still holds something shows until it is emptied.
 */
public class BackpackSlot extends Slot {
    /** Set by the client while the creative inventory is open, where these slots would sit on top of the hotbar. */
    public static volatile boolean hiddenOnClient;
    private final Player player;
    private final int number;

    public BackpackSlot(Player player, Container container, int number, int x, int y) {
        super(container, number, x, y);
        this.player = player;
        this.number = number;
    }

    public int number() {
        return number;
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
