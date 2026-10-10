package com.meakaandre.siftec.backpack;

import com.meakaandre.siftec.mixin.MenuInvoker;
import com.meakaandre.siftec.mixin.SlotAccessor;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The backpack as extra rows of the normal inventory, in every window that shows the player's inventory.
 * <p>
 * A window gets the rows when its slots hold the player's 27 main slots as a 9x3 grid and the 9 hotbar slots in a row
 * under it, wherever they were added from (vanilla's helper, Create's menus, other mods). The 36 backpack slots are
 * added after all of the window's own slots, so no slot number the window already uses moves. On screen they sit
 * between the main rows and the hotbar; the hotbar, and anything else below that line, moves down by the rows shown
 * (on the client only: positions mean nothing on the server).
 * <p>
 * Server and client decide the same way from the same slot positions: the server when it opens the window
 * ({@code ServerPlayer.initMenu}), the client when it makes the screen or handles the open packet, and both for the
 * player's own inventory when it is made.
 */
public final class BackpackRows {
    /** Where the rows of one window are. */
    public static final class Layout {
        /** Slot number of the first backpack slot; the 36 follow. */
        public final int first;
        /** Slot numbers of the main inventory (container slots 9..35, in order) and of the hotbar (0..8). */
        public final int[] main, hotbar;
        /** Position of the first main slot. */
        public final int left, top;
        /** The slots under the main rows, and where the window put them. */
        final List<Slot> below = new ArrayList<>();
        final List<Integer> belowY = new ArrayList<>();
        /** How many backpack rows the slots are placed for now. */
        public int placedRows;

        Layout(int first, int[] main, int[] hotbar, int left, int top) {
            this.first = first;
            this.main = main;
            this.hotbar = hotbar;
            this.left = left;
            this.top = top;
        }

        public boolean isBackpack(int slot) {
            return slot >= first && slot < first + Backpack.SIZE;
        }

        /** The line, in window coordinates, where the backpack rows go in: just under the frames of the main rows. */
        public int split() {
            return top + 53;
        }
    }

    /** What every menu carries (mixed into AbstractContainerMenu). */
    public interface Holder {
        Layout siftec$backpack();

        void siftec$setBackpack(Layout layout);
    }

    private BackpackRows() {
    }

    public static Layout of(AbstractContainerMenu menu) {
        return menu == null ? null : ((Holder) menu).siftec$backpack();
    }

    /** Adds the backpack rows to a window that shows the player's inventory in the usual layout. Safe to call twice. */
    public static void attach(AbstractContainerMenu menu, Player player) {
        if (menu == null || player == null || of(menu) != null) return;
        int[] main = new int[27], hotbar = new int[9];
        java.util.Arrays.fill(main, -1);
        java.util.Arrays.fill(hotbar, -1);
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (slot instanceof BackpackSlot) return;
            if (!(slot.container instanceof Inventory inventory) || inventory.player != player) continue;
            int n = slot.getContainerSlot();
            if (n >= 9 && n < 36 && main[n - 9] < 0) main[n - 9] = i;
            else if (n >= 0 && n < 9 && hotbar[n] < 0) hotbar[n] = i;
        }
        for (int i : main) if (i < 0) return;
        for (int i : hotbar) if (i < 0) return;
        Slot origin = menu.slots.get(main[0]);
        int x0 = origin.x, y0 = origin.y;
        // a window that keeps the inventory off screen (Create's stock keeper puts it at -1000) gets no rows
        if (x0 < -100 || y0 < -100 || x0 > 2000 || y0 > 2000) return;
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 9; c++) {
                Slot slot = menu.slots.get(main[r * 9 + c]);
                if (slot.x != x0 + c * 18 || slot.y != y0 + r * 18) return;
            }
        }
        int hotbarY = menu.slots.get(hotbar[0]).y;
        if (hotbarY < y0 + 54) return;
        for (int c = 0; c < 9; c++) {
            Slot slot = menu.slots.get(hotbar[c]);
            if (slot.x != x0 + c * 18 || slot.y != hotbarY) return;
        }
        Layout layout = new Layout(menu.slots.size(), main, hotbar, x0, y0);
        for (int i = 0; i < layout.first; i++) {
            Slot slot = menu.slots.get(i);
            if (slot.y >= y0 + 54 && slot.y < 2000) {
                layout.below.add(slot);
                layout.belowY.add(slot.y);
            }
        }
        BackpackContainer container = new BackpackContainer(player);
        for (int i = 0; i < Backpack.SIZE; i++) {
            ((MenuInvoker) menu).siftec$addSlot(new BackpackSlot(player, container, i, x0 + (i % 9) * 18, y0 + 54 + (i / 9) * 18));
        }
        ((Holder) menu).siftec$setBackpack(layout);
    }

    /** How many backpack slots a window shows: the open ones, and any later slot that still holds something. */
    public static int shown(AbstractContainerMenu menu, Player player) {
        Layout layout = of(menu);
        if (layout == null || player == null) return 0;
        int shown = Backpack.unlocked(player);
        for (int i = Backpack.SIZE - 1; i >= shown; i--) {
            if (menu.slots.get(layout.first + i).hasItem()) return i + 1;
        }
        return shown;
    }

    public static int rows(int shown) {
        return (shown + 8) / 9;
    }

    /** Moves the hotbar (and anything else under the main rows) down to make room for {@code rows} backpack rows. */
    public static void place(Layout layout, int rows) {
        if (layout == null || layout.placedRows == rows) return;
        for (int i = 0; i < layout.below.size(); i++) {
            ((SlotAccessor) layout.below.get(i)).siftec$setY(layout.belowY.get(i) + rows * 18);
        }
        layout.placedRows = rows;
    }

    /**
     * Shift-click on a backpack slot does what shift-click on a main inventory slot does in that window (into the
     * chest, the furnace's input, the hotbar...): the two slots swap for the length of the move and swap back after.
     * Nothing is copied: whole stacks only ever change places.
     */
    public static void quickMove(AbstractContainerMenu menu, Layout layout, Slot backpack, Player player) {
        Slot main = menu.slots.get(layout.main[0]);
        ItemStack fromBackpack = backpack.getItem(), fromMain = main.getItem();
        backpack.container.setItem(backpack.getContainerSlot(), fromMain);
        main.container.setItem(main.getContainerSlot(), fromBackpack);
        try {
            int guard = 0;
            for (ItemStack moved = menu.quickMoveStack(player, layout.main[0]);
                 !moved.isEmpty() && ItemStack.isSameItem(main.getItem(), moved) && guard < 64;
                 moved = menu.quickMoveStack(player, layout.main[0])) {
                guard++;
            }
        } finally {
            ItemStack left = main.getItem(), back = backpack.getItem();
            main.container.setItem(main.getContainerSlot(), back);
            backpack.container.setItem(backpack.getContainerSlot(), left);
            main.setChanged();
            backpack.setChanged();
        }
    }

    /**
     * {@code moveItemStackTo} for a range that reaches the player's inventory: the window's own slots in the range
     * first, in the order asked for, then the backpack from its first slot. A range that covers the whole main
     * inventory (the "to the player" moves) gets the backpack added even when it stops before it, so hotbar to
     * main and container to player both spill into the backpack once the main rows are full. Returns null when
     * the range has nothing to do with the backpack and the game's own code should run.
     */
    public static Boolean moveTo(AbstractContainerMenu menu, ItemStack stack, int start, int end, boolean reverse) {
        Layout layout = of(menu);
        if (layout == null) return null;
        int bpStart = layout.first, bpEnd = layout.first + Backpack.SIZE;
        boolean touches = start < bpEnd && end > bpStart;
        boolean coversMain = true;
        for (int i : layout.main) {
            if (i < start || i >= end) {
                coversMain = false;
                break;
            }
        }
        if (!touches && !coversMain) return null;
        boolean fromBackpack = false;
        for (int i = bpStart; i < bpEnd && i < menu.slots.size(); i++) {
            if (menu.slots.get(i).getItem() == stack) fromBackpack = true;
        }
        List<Slot> order = new ArrayList<>();
        int lo = Math.max(0, start), hi = Math.min(end, menu.slots.size());
        if (reverse) {
            for (int i = hi - 1; i >= lo; i--) if (!layout.isBackpack(i)) order.add(menu.slots.get(i));
        } else {
            for (int i = lo; i < hi; i++) if (!layout.isBackpack(i)) order.add(menu.slots.get(i));
        }
        int backpackFrom = order.size();
        if (!fromBackpack) {
            for (int i = bpStart; i < bpEnd && i < menu.slots.size(); i++) order.add(menu.slots.get(i));
        }
        boolean moved = false;
        if (stack.isStackable()) {
            for (int k = 0; k < order.size() && !stack.isEmpty(); k++) {
                Slot slot = order.get(k);
                ItemStack target = slot.getItem();
                if (target == stack || target.isEmpty() || !ItemStack.isSameItemSameComponents(stack, target)) continue;
                if (k >= backpackFrom && !slot.mayPlace(stack)) continue;
                int max = slot.getMaxStackSize(target);
                int total = target.getCount() + stack.getCount();
                if (total <= max) {
                    stack.setCount(0);
                    target.setCount(total);
                    slot.setChanged();
                    moved = true;
                } else if (target.getCount() < max) {
                    stack.shrink(max - target.getCount());
                    target.setCount(max);
                    slot.setChanged();
                    moved = true;
                }
            }
        }
        if (!stack.isEmpty()) {
            for (Slot slot : order) {
                if (slot.getItem().isEmpty() && slot.mayPlace(stack)) {
                    slot.setByPlayer(stack.split(Math.min(slot.getMaxStackSize(stack), stack.getCount())));
                    slot.setChanged();
                    moved = true;
                    break;
                }
            }
        }
        return moved;
    }
}
