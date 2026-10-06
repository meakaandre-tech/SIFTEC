package com.meakaandre.siftec.sink;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.hub.Milestones;
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

/** The AWESOME Shop: spend the company's Sink points. Run by the server, like the HUB window. */
public class ShopMenu extends ChestMenu {
    private static final int SIZE = 54;

    private record Offer(Item item, int count, int price) {
    }

    private final SimpleContainer view;
    private final ServerPlayer player;
    private final Company company;
    private final List<Offer> offers = new ArrayList<>();

    private ShopMenu(int id, Inventory inventory, SimpleContainer view, ServerPlayer player, Company company) {
        super(MenuType.GENERIC_9x6, id, inventory, view, 6);
        this.view = view;
        this.player = player;
        this.company = company;
        for (JsonElement e : Milestones.raw().getAsJsonArray("shop")) {
            JsonObject o = e.getAsJsonObject();
            Item item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(o.get("item").getAsString())).orElse(Items.AIR);
            if (item != Items.AIR && offers.size() < SIZE) offers.add(new Offer(item, o.get("count").getAsInt(), o.get("price").getAsInt()));
        }
        refresh();
    }

    public static void open(ServerPlayer player, Company company) {
        player.openMenu(new SimpleMenuProvider(
            (id, inventory, p) -> new ShopMenu(id, inventory, new SimpleContainer(SIZE), player, company),
            Component.translatable("siftec.shop.title", company.points)));
    }

    private void refresh() {
        for (int i = 0; i < offers.size(); i++) {
            Offer offer = offers.get(i);
            ItemStack icon = new ItemStack(offer.item(), offer.count());
            boolean can = company.points >= offer.price();
            icon.set(DataComponents.LORE, new ItemLore(List.of(
                Component.empty().append(Component.translatable("siftec.shop.price", offer.price())).withStyle(s -> s.withItalic(false).withColor(can ? ChatFormatting.GREEN : ChatFormatting.RED)),
                Component.empty().append(Component.translatable("siftec.sink.points", company.points)).withStyle(s -> s.withItalic(false).withColor(ChatFormatting.GRAY)))));
            view.setItem(i, icon);
        }
    }

    private void press(int slot) {
        if (slot >= offers.size()) return;
        Offer offer = offers.get(slot);
        if (company.points < offer.price()) {
            player.sendOverlayMessage(Component.translatable("siftec.shop.poor"));
            return;
        }
        company.points -= offer.price();
        Companies.save(player.level().getServer());
        player.getInventory().placeItemBackInInventory(new ItemStack(offer.item(), offer.count()), Prediction.SERVER_ONLY);
        player.sendOverlayMessage(Component.translatable("siftec.sink.points", company.points));
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
