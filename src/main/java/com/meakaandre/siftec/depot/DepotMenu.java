package com.meakaandre.siftec.depot;

import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.Company;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
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
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/** The company's cloud inventory. Click an item to take a stack; the last row turns the page. */
public class DepotMenu extends ChestMenu {
    private static final int SIZE = 54, PER_PAGE = 45, PREVIOUS = 45, NEXT = 53;
    private final SimpleContainer view;
    private final ServerPlayer player;
    private final Company company;
    private final List<String> shown = new ArrayList<>();
    private int page;
    private int ticks;

    private DepotMenu(int id, Inventory inventory, SimpleContainer view, ServerPlayer player, Company company) {
        super(MenuType.GENERIC_9x6, id, inventory, view, 6);
        this.view = view;
        this.player = player;
        this.company = company;
        refresh();
    }

    public static void open(ServerPlayer player, Company company) {
        player.openMenu(new SimpleMenuProvider(
            (id, inventory, p) -> new DepotMenu(id, inventory, new SimpleContainer(SIZE), player, company),
            Component.translatable("siftec.depot.title", company.name)));
    }

    private void refresh() {
        shown.clear();
        for (int i = 0; i < SIZE; i++) view.setItem(i, ItemStack.EMPTY);
        List<String> ids = new ArrayList<>(new TreeMap<>(company.cloud).keySet());
        int pages = Math.max(1, (ids.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.min(page, pages - 1);
        for (int i = page * PER_PAGE; i < ids.size() && shown.size() < PER_PAGE; i++) {
            String id = ids.get(i);
            Item item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(id)).orElse(Items.AIR);
            int count = company.cloud.getOrDefault(id, 0);
            if (item == Items.AIR || count <= 0) continue;
            ItemStack icon = new ItemStack(item);
            icon.set(DataComponents.LORE, new ItemLore(List.of(
                Component.empty().append(Component.translatable("siftec.depot.count", count)).withStyle(s -> s.withItalic(false).withColor(ChatFormatting.AQUA)),
                Component.empty().append(Component.translatable("siftec.depot.click")).withStyle(s -> s.withItalic(false).withColor(ChatFormatting.YELLOW)))));
            view.setItem(shown.size(), icon);
            shown.add(id);
        }
        if (page > 0) view.setItem(PREVIOUS, named(Items.ARROW, "siftec.depot.previous"));
        if (page < pages - 1) view.setItem(NEXT, named(Items.ARROW, "siftec.depot.next"));
    }

    private static ItemStack named(Item item, String key) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, Component.empty().append(Component.translatable(key)).withStyle(s -> s.withItalic(false)));
        return stack;
    }

    private void press(int slot) {
        if (slot == PREVIOUS && page > 0) page--;
        else if (slot == NEXT) page++;
        else if (slot < shown.size()) {
            String id = shown.get(slot);
            Item item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(id)).orElse(Items.AIR);
            int have = company.cloud.getOrDefault(id, 0);
            int take = Math.min(have, new ItemStack(item).getMaxStackSize());
            if (item != Items.AIR && take > 0) {
                if (have - take <= 0) company.cloud.remove(id);
                else company.cloud.put(id, have - take);
                player.getInventory().placeItemBackInInventory(new ItemStack(item, take), Prediction.SERVER_ONLY);
                Companies.save(player.level().getServer());
            }
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
    public void broadcastChanges() {
        if (++ticks % 20 == 0) refresh();
        super.broadcastChanges();
    }

    @Override
    public boolean stillValid(Player who) {
        return true;
    }
}
