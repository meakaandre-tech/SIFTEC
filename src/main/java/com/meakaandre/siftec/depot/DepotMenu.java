package com.meakaandre.siftec.depot;

import com.meakaandre.siftec.building.BuildingMenus;
import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.Company;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
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

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * The company's cloud inventory. Click an item to take a stack; the last row turns the page. With the Manual Depot
 * Uploader research, shift-clicking a stack in the player's own inventory uploads it.
 */
public class DepotMenu extends ChestMenu {
    private static final int SIZE = 54, PER_PAGE = 45, PREVIOUS = 45, NEXT = 53;
    /** Rows of the chest window. */
    private static final int ROWS = 6;
    /** The window is refreshed this often (ticks). */
    private static final int REFRESH_TICKS = 20;
    private final SimpleContainer view;
    private final ServerPlayer player;
    private final Company company;
    /** The depot it was opened at, or null when opened from anywhere (the Cloud button, /depot). */
    private final @Nullable BlockPos at;
    private final List<String> shown = new ArrayList<>();
    private int page;
    private int ticks;

    private DepotMenu(int id, Inventory inventory, SimpleContainer view, ServerPlayer player, Company company, @Nullable BlockPos at) {
        super(MenuType.GENERIC_9x6, id, inventory, view, ROWS);
        this.view = view;
        this.player = player;
        this.company = company;
        this.at = at == null ? null : at.immutable();
        refresh();
    }

    public static void open(ServerPlayer player, Company company) {
        open(player, company, null);
    }

    /** Opens the cloud; {@code at} is the depot it was opened at (it must stay there and in reach), or null. */
    public static void open(ServerPlayer player, Company company, @Nullable BlockPos at) {
        player.openMenu(new SimpleMenuProvider(
            (id, inventory, p) -> new DepotMenu(id, inventory, new SimpleContainer(SIZE), player, company, at),
            Component.translatable("siftec.depot.title", company.name)));
    }

    /** Blocks a player can be from the depot they opened the cloud at and keep it open. */
    public static final double REACH = 8.0;

    /**
     * Whether the window stays open: the player is still there and alive, still a member of the company whose cloud it
     * shows, and, when it was opened at a depot, that depot still stands and is within reach.
     */
    public static boolean valid(ServerPlayer player, Company company, @Nullable BlockPos at) {
        if (!BuildingMenus.alive(player)) return false;
        if (!BuildingMenus.member(player, company.id)) return false;
        if (at == null) return true;
        if (!(player.level().getBlockEntity(at) instanceof DepotBlockEntity)) return false;
        return BuildingMenus.withinReach(player, at, REACH);
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
            ItemStack taken = DepotBlockEntity.withdraw(company, shown.get(slot));
            if (!taken.isEmpty()) {
                player.getInventory().placeItemBackInInventory(taken, Prediction.SERVER_ONLY);
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
        if (input == ContainerInput.QUICK_MOVE && slot >= SIZE && slot < slots.size()) {
            uploadFrom(slot);
            return;
        }
        if (input == ContainerInput.PICKUP || input == ContainerInput.THROW) super.clicked(slot, button, input, who);
    }

    /** Manual Depot Uploader: puts the stack in one of the player's slots into the cloud, as much as there is room for. */
    private void uploadFrom(int slot) {
        if (!DepotBlockEntity.manual(company) && !player.hasInfiniteMaterials()) {
            player.sendOverlayMessage(Component.translatable("siftec.depot.manual_locked"));
            return;
        }
        ItemStack stack = slots.get(slot).getItem();
        if (stack.isEmpty()) return;
        Component name = stack.getHoverName();
        int n = DepotBlockEntity.upload(company, stack);
        slots.get(slot).setChanged();
        player.sendOverlayMessage(n > 0 ? Component.translatable("siftec.depot.uploaded", n, name)
            : Component.translatable("siftec.depot.full"));
        if (n > 0) Companies.save(player.level().getServer());
        refresh();
    }

    @Override
    public ItemStack quickMoveStack(Player who, int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public void broadcastChanges() {
        if (++ticks % REFRESH_TICKS == 0) refresh();
        super.broadcastChanges();
    }

    @Override
    public boolean stillValid(Player who) {
        return who == player && valid(player, company, at);
    }
}
