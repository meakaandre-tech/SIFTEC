package com.meakaandre.siftec.sink;

import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.cosmetic.Outfits;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The Customizer used in the air: the helmet skins and suit trims the company has bought (AWESOME Shop), put on what
 * the player wears. The top row is helmet looks (the first is the helmet's own), the third row suit trims (the first
 * takes the trim off).
 */
public class OutfitMenu extends ChestMenu {
    private static final int SIZE = 27, HELMETS = 0, TRIMS = 18;
    /** Slots in a row of the window, and its rows. */
    private static final int ROW = 9, ROWS = 3;
    private final SimpleContainer view;
    private final ServerPlayer player;
    private final Company company;

    private OutfitMenu(int id, Inventory inventory, SimpleContainer view, ServerPlayer player, Company company) {
        super(MenuType.GENERIC_9x3, id, inventory, view, ROWS);
        this.view = view;
        this.player = player;
        this.company = company;
        refresh();
    }

    public static void open(ServerPlayer player) {
        Company company = Companies.of(player);
        player.openMenu(new SimpleMenuProvider((id, inventory, p) -> new OutfitMenu(id, inventory, new SimpleContainer(SIZE), player, company),
            Component.translatable("siftec.outfit.title")));
    }

    private static Item item(String id) {
        return BuiltInRegistries.ITEM.getOptional(Identifier.parse(id)).orElse(Items.BARRIER);
    }

    private void refresh() {
        for (int i = 0; i < SIZE; i++) view.setItem(i, ItemStack.EMPTY);
        view.setItem(HELMETS, SinkMenu.button(Items.IRON_HELMET, Component.translatable("siftec.outfit.helmet_own"), List.of()));
        int k = 1;
        for (String id : Shop.helmets().keySet()) {
            if (k >= ROW) break;
            Shop.Entry e = Shop.entry("helmet_" + id);
            boolean open = Shop.owns(player, company, "helmet:" + id);
            view.setItem(HELMETS + k++, SinkMenu.button(open && e != null ? ShopMenu.icon(e) : Items.BARRIER, Component.translatable("siftec.shop.entry.helmet_" + id),
                open ? List.of() : List.of(Component.translatable("siftec.customizer.locked").withStyle(ChatFormatting.RED))));
        }
        view.setItem(TRIMS, SinkMenu.button(Items.SMITHING_TABLE, Component.translatable("siftec.outfit.trim_none"), List.of()));
        k = 1;
        for (Map.Entry<String, String[]> t : Shop.trims().entrySet()) {
            if (k >= ROW) break;
            boolean open = Shop.owns(player, company, "trim:" + t.getKey());
            view.setItem(TRIMS + k++, SinkMenu.button(open ? item(t.getValue()[0] + "_armor_trim_smithing_template") : Items.BARRIER,
                Component.translatable("siftec.shop.entry.trim_" + t.getKey()),
                open ? List.of() : List.of(Component.translatable("siftec.customizer.locked").withStyle(ChatFormatting.RED))));
        }
    }

    private void press(int slot) {
        List<String> helmets = new ArrayList<>(Shop.helmets().keySet()), trims = new ArrayList<>(Shop.trims().keySet());
        if (slot >= HELMETS && slot < HELMETS + ROW) {
            String id = slot == HELMETS ? "" : slot - HELMETS - 1 < helmets.size() ? helmets.get(slot - HELMETS - 1) : null;
            if (id == null) return;
            if (!id.isEmpty() && !Shop.owns(player, company, "helmet:" + id)) {
                player.sendOverlayMessage(Component.translatable("siftec.customizer.locked"));
                return;
            }
            player.sendOverlayMessage(Component.translatable(Outfits.helmet(player, id) ? "siftec.outfit.helmet_done" : "siftec.outfit.no_helmet"));
        } else if (slot >= TRIMS && slot < TRIMS + ROW) {
            String id = slot == TRIMS ? "" : slot - TRIMS - 1 < trims.size() ? trims.get(slot - TRIMS - 1) : null;
            if (id == null) return;
            if (!id.isEmpty() && !Shop.owns(player, company, "trim:" + id)) {
                player.sendOverlayMessage(Component.translatable("siftec.customizer.locked"));
                return;
            }
            int pieces = Outfits.trim(player, id);
            player.sendOverlayMessage(pieces == 0 ? Component.translatable("siftec.outfit.no_armour") : Component.translatable("siftec.outfit.trim_done", pieces));
        }
        refresh();
    }

    @Override
    public void clicked(int slot, int button, ContainerInput input, Player who) {
        if (slot >= 0 && slot < SIZE) {
            if (input == ContainerInput.PICKUP || input == ContainerInput.QUICK_MOVE) press(slot);
            return;
        }
        if (input == ContainerInput.PICKUP || input == ContainerInput.THROW) super.clicked(slot, button, input, who);
    }

    @Override
    public ItemStack quickMoveStack(Player who, int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player who) {
        return true;
    }
}
