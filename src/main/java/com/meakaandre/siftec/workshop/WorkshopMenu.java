package com.meakaandre.siftec.workshop;

import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.hub.Locks;
import com.meakaandre.siftec.hub.Milestone;
import com.meakaandre.siftec.hub.Milestones;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
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

/** The Equipment Workshop window: one button per thing the company can build. Run by the server, like the HUB. */
public class WorkshopMenu extends ChestMenu {
    private static final int SIZE = 54;
    private final SimpleContainer view;
    private final ServerPlayer player;
    private final Company company;
    private final List<Milestones.Build> shown = new ArrayList<>();

    private WorkshopMenu(int id, Inventory inventory, SimpleContainer view, ServerPlayer player, Company company) {
        super(MenuType.GENERIC_9x6, id, inventory, view, 6);
        this.view = view;
        this.player = player;
        this.company = company;
        refresh();
    }

    public static void open(ServerPlayer player, Company company) {
        player.openMenu(new SimpleMenuProvider(
            (id, inventory, p) -> new WorkshopMenu(id, inventory, new SimpleContainer(SIZE), player, company),
            Component.translatable("siftec.workshop.title")));
    }

    private void refresh() {
        shown.clear();
        for (int i = 0; i < SIZE; i++) view.setItem(i, ItemStack.EMPTY);
        for (Milestones.Build build : Milestones.workshop()) {
            Item result = BuiltInRegistries.ITEM.getOptional(build.item()).orElse(Items.AIR);
            if (result == Items.AIR || shown.size() >= SIZE) continue;
            Milestone lock = Locks.lockOf(result);
            if (lock != null && !company.has(lock.id()) && !player.hasInfiniteMaterials()) continue;
            ItemStack icon = new ItemStack(result);
            List<Component> lore = new ArrayList<>();
            for (Milestone.Cost cost : build.cost()) {
                if (!cost.present()) continue;
                int have = Math.min(cost.carried(player.getInventory()), cost.count());
                lore.add(Component.empty().append(Component.translatable("siftec.hub.cost", cost.label(), have, cost.count()))
                    .withStyle(style -> style.withItalic(false).withColor(have >= cost.count() ? ChatFormatting.GREEN : ChatFormatting.WHITE)));
            }
            lore.add(Component.empty().append(Component.translatable("siftec.workshop.click"))
                .withStyle(style -> style.withItalic(false).withColor(ChatFormatting.YELLOW)));
            icon.set(DataComponents.LORE, new ItemLore(lore));
            view.setItem(shown.size(), icon);
            shown.add(build);
        }
    }

    private void press(int slot) {
        if (slot >= shown.size()) return;
        Milestones.Build build = shown.get(slot);
        Inventory inventory = player.getInventory();
        for (Milestone.Cost cost : build.cost()) {
            if (cost.present() && cost.carried(inventory) < cost.count()) {
                player.sendOverlayMessage(Component.translatable("siftec.workshop.missing"));
                return;
            }
        }
        for (Milestone.Cost cost : build.cost()) {
            if (cost.present()) cost.take(inventory, cost.count());
        }
        ItemStack made = new ItemStack(BuiltInRegistries.ITEM.getValue(build.item()));
        player.sendOverlayMessage(Component.translatable("siftec.workshop.built", made.getItemName()));
        inventory.placeItemBackInInventory(made, Prediction.SERVER_ONLY);
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
