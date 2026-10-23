package com.meakaandre.siftec.compat;

import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ArmorSlot;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The armour and offhand slots the backpack window shows, since it opens in place of the vanilla inventory: a column
 * on the window's left, helmet to boots, then the offhand. Added to the menu of the player's own worn backpack on
 * both sides alike (mixin/tb/TbMenuMixin), drawn by {@link TbClient}. Plain vanilla slots over the player's
 * inventory, so armour behaves exactly as in the vanilla window.
 */
public final class TbEquipSlots {
    /** Helmet to boots, top to bottom, as the vanilla inventory shows them. */
    public static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    private static final Identifier[] ICONS = {InventoryMenu.EMPTY_ARMOR_SLOT_HELMET, InventoryMenu.EMPTY_ARMOR_SLOT_CHESTPLATE,
        InventoryMenu.EMPTY_ARMOR_SLOT_LEGGINGS, InventoryMenu.EMPTY_ARMOR_SLOT_BOOTS};
    /** The vanilla inventory's first armour slot (armour slots are this plus the piece's index). */
    private static final int ARMOR_INVENTORY_START = 36;
    /** Where the column's slots sit, relative to the window's left and top edge. */
    public static final int X = -21;
    public static final int TOP = 8;
    /** The panel around the column, relative to the window. */
    public static final int PANEL_X = -27, PANEL_Y = 2, PANEL_W = 28, PANEL_H = 4 * 18 + 6 + 18 + 12;

    /** The offhand, slot 40 of the inventory. */
    public static final class OffhandSlot extends Slot {
        private final Player owner;

        public OffhandSlot(Inventory inventory, int x, int y) {
            super(inventory, Inventory.SLOT_OFFHAND, x, y);
            this.owner = inventory.player;
        }

        @Override
        public void setByPlayer(ItemStack stack, ItemStack previous) {
            owner.onEquipItem(EquipmentSlot.OFFHAND, previous, stack);
            super.setByPlayer(stack, previous);
        }

        @Override
        public Identifier getNoItemIcon() {
            return InventoryMenu.EMPTY_ARMOR_SLOT_SHIELD;
        }
    }

    private TbEquipSlots() {
    }

    public static int y(int row) {
        return row < ARMOR.length ? TOP + row * 18 : TOP + ARMOR.length * 18 + 6;
    }

    /** The five slots, in the order they are added. */
    public static List<Slot> create(Inventory inventory) {
        List<Slot> out = new ArrayList<>();
        for (int i = 0; i < ARMOR.length; i++) {
            EquipmentSlot slot = ARMOR[i];
            out.add(new ArmorSlot(inventory, inventory.player, slot, ARMOR_INVENTORY_START + slot.getIndex(), X, y(i), ICONS[i]));
        }
        out.add(new OffhandSlot(inventory, X, y(ARMOR.length)));
        return out;
    }

    public static boolean isEquipSlot(Slot slot) {
        return slot instanceof ArmorSlot || slot instanceof OffhandSlot;
    }

    /** The menu's slot for this armour piece, or -1. */
    public static int find(AbstractContainerMenu menu, EquipmentSlot equipment) {
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (slot instanceof ArmorSlot && slot.container instanceof Inventory && slot.getContainerSlot() == ARMOR_INVENTORY_START + equipment.getIndex()) return i;
        }
        return -1;
    }

    /** True when the menu shows the column. */
    public static boolean shown(AbstractContainerMenu menu) {
        for (Slot slot : menu.slots) if (isEquipSlot(slot)) return true;
        return false;
    }

    /** True for a point inside the column's panel, relative to the window. */
    public static boolean inPanel(double x, double y) {
        return x >= PANEL_X && x < PANEL_X + PANEL_W && y >= PANEL_Y && y < PANEL_Y + PANEL_H;
    }
}
