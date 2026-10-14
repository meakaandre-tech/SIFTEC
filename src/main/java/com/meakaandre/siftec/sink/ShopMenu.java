package com.meakaandre.siftec.sink;

import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.hub.Milestone;
import com.meakaandre.siftec.hub.Milestones;
import com.meakaandre.siftec.registry.ModBlocks;
import com.meakaandre.siftec.registry.ModItems;
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

import java.util.ArrayList;
import java.util.List;

/**
 * The AWESOME Shop window: every cosmetic, its price in FICSIT Coupons, and whether the company owns it. Clicking one
 * buys it with coupons from the inventory; clicking a decoration or cup the company owns takes one for its part cost.
 * Run by the server, like the HUB window.
 */
public class ShopMenu extends ChestMenu {
    private static final int SIZE = 54, PER_PAGE = 45, PREVIOUS = 45, INFO = 49, NEXT = 53;
    private final SimpleContainer view;
    private final ServerPlayer player;
    private final Company company;
    private int page;

    private ShopMenu(int id, Inventory inventory, SimpleContainer view, ServerPlayer player, Company company) {
        super(MenuType.GENERIC_9x6, id, inventory, view, 6);
        this.view = view;
        this.player = player;
        this.company = company;
        refresh();
    }

    public static void open(ServerPlayer player, Company company) {
        player.openMenu(new SimpleMenuProvider(
            (id, inventory, p) -> new ShopMenu(id, inventory, new SimpleContainer(SIZE), player, company),
            Component.translatable("siftec.shop.title", coupons(player))));
    }

    private static int coupons(ServerPlayer player) {
        return player.getInventory().countItem(ModItems.FICSIT_COUPON.get());
    }

    /** The picture for an entry: the thing itself where it is an item, a stand-in otherwise. */
    static Item icon(Shop.Entry e) {
        return switch (e.kind()) {
            case "customizer" -> ModItems.CUSTOMIZER.get();
            case "swatch" -> item("minecraft:orange_dye");
            case "finish" -> switch (e.arg()) {
                case "copper" -> item("minecraft:copper_block");
                case "chrome" -> Items.IRON_BLOCK;
                case "carbon_steel" -> Items.NETHERITE_BLOCK;
                case "caterium" -> Items.GOLD_BLOCK;
                default -> item("minecraft:gray_concrete");
            };
            case "skin" -> {
                String block = Shop.skins().getOrDefault(e.arg(), Shop.storageSkins().getOrDefault(e.arg(), "minecraft:barrier"));
                yield BuiltInRegistries.ITEM.getOptional(Identifier.parse(block)).orElse(Items.BARRIER);
            }
            case "decor" -> ModBlocks.DECOR.containsKey(e.arg()) ? ModBlocks.DECOR.get(e.arg()).asItem() : Items.BARRIER;
            case "cup" -> ModItems.CUPS.containsKey(e.arg()) ? ModItems.CUPS.get(e.arg()) : Items.BARRIER;
            default -> Items.BARRIER;
        };
    }

    private static Item item(String id) {
        return BuiltInRegistries.ITEM.getOptional(Identifier.parse(id)).orElse(Items.BARRIER);
    }

    private List<Shop.Entry> shown() {
        return Shop.entries();
    }

    private int pages() {
        return Math.max(1, (shown().size() + PER_PAGE - 1) / PER_PAGE);
    }

    private void refresh() {
        for (int i = 0; i < SIZE; i++) view.setItem(i, ItemStack.EMPTY);
        List<Shop.Entry> entries = shown();
        int have = coupons(player);
        for (int i = 0; i < PER_PAGE && page * PER_PAGE + i < entries.size(); i++) {
            Shop.Entry e = entries.get(page * PER_PAGE + i);
            boolean owned = e.owned(company);
            List<Component> lore = new ArrayList<>();
            lore.add(Component.translatable("siftec.shop.category." + e.category().toLowerCase(java.util.Locale.ROOT).replace(' ', '_')).withStyle(ChatFormatting.DARK_GRAY));
            if (e.kind().equals("swatch")) lore.add(Component.translatable("siftec.shop.swatches", company.swatches.size(), Shop.MAX_SWATCHES).withStyle(ChatFormatting.GRAY));
            if (owned) {
                lore.add(Component.translatable(e.kind().equals("swatch") ? "siftec.shop.full" : "siftec.shop.owned").withStyle(ChatFormatting.GREEN));
                Shop.Goods goods = e.kind().equals("decor") || e.kind().equals("cup") ? Shop.goods(e.arg()) : null;
                if (goods != null) {
                    for (Milestone.Cost cost : goods.cost()) {
                        int carried = Math.min(cost.carried(player.getInventory()), cost.count());
                        lore.add(Component.translatable("siftec.hub.cost", cost.label(), carried, cost.count())
                            .withStyle(carried >= cost.count() ? ChatFormatting.GREEN : ChatFormatting.WHITE));
                    }
                    if (!goods.needs().isEmpty() && !company.has(goods.needs()) && Milestones.get(goods.needs()) != null) {
                        lore.add(Component.translatable("siftec.shop.needs", Milestones.get(goods.needs()).name()).withStyle(ChatFormatting.RED));
                    } else {
                        lore.add(Component.translatable("siftec.shop.take_free").withStyle(ChatFormatting.YELLOW));
                    }
                } else if (e.kind().equals("customizer")) {
                    lore.add(Component.translatable("siftec.shop.take_free").withStyle(ChatFormatting.YELLOW));
                }
            } else {
                lore.add(Component.translatable("siftec.shop.price", e.coupons()).withStyle(have >= e.coupons() ? ChatFormatting.GREEN : ChatFormatting.RED));
            }
            ItemStack stack = SinkMenu.button(icon(e), Component.translatable("siftec.shop.entry." + e.id()), lore);
            if (owned) stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
            view.setItem(i, stack);
        }
        if (page > 0) view.setItem(PREVIOUS, SinkMenu.button(Items.ARROW, Component.translatable("siftec.shop.previous"), List.of()));
        if (page < pages() - 1) view.setItem(NEXT, SinkMenu.button(Items.ARROW, Component.translatable("siftec.shop.next"), List.of()));
        view.setItem(INFO, SinkMenu.button(ModItems.FICSIT_COUPON.get(), Component.translatable("siftec.shop.title", have),
            List.of(Component.translatable("siftec.shop.page", page + 1, pages()).withStyle(ChatFormatting.GRAY))));
    }

    private void press(int slot) {
        if (slot == PREVIOUS && page > 0) {
            page--;
            refresh();
            return;
        }
        if (slot == NEXT && page < pages() - 1) {
            page++;
            refresh();
            return;
        }
        int index = page * PER_PAGE + slot;
        List<Shop.Entry> entries = shown();
        if (slot >= PER_PAGE || index >= entries.size()) return;
        Shop.Entry e = entries.get(index);
        if (e.owned(company)) {
            take(e);
        } else {
            buy(e);
        }
        Companies.save(player.level().getServer());
        refresh();
    }

    private void buy(Shop.Entry e) {
        Item coupon = ModItems.FICSIT_COUPON.get();
        if (!player.hasInfiniteMaterials()) {
            if (player.getInventory().countItem(coupon) < e.coupons()) {
                player.sendOverlayMessage(Component.translatable("siftec.shop.poor"));
                return;
            }
            new Milestone.Cost(BuiltInRegistries.ITEM.getKey(coupon).toString(), e.coupons()).take(player.getInventory(), e.coupons());
        }
        Shop.unlock(company, e);
        Companies.tell(player.level().getServer(), company,
            Component.translatable("siftec.shop.bought", Component.translatable("siftec.shop.entry." + e.id())).withStyle(ChatFormatting.GOLD));
    }

    /** One of something the company owns: the Customizer free, a decoration or cup for its parts. */
    private void take(Shop.Entry e) {
        ItemStack made;
        switch (e.kind()) {
            case "customizer" -> made = new ItemStack(ModItems.CUSTOMIZER.get());
            case "decor", "cup" -> {
                Shop.Goods goods = Shop.goods(e.arg());
                if (goods == null) return;
                if (!goods.needs().isEmpty() && !company.has(goods.needs()) && !player.hasInfiniteMaterials()) {
                    Milestone needs = Milestones.get(goods.needs());
                    player.sendOverlayMessage(Component.translatable("siftec.shop.needs", needs == null ? Component.literal(goods.needs()) : needs.name()));
                    return;
                }
                if (!player.hasInfiniteMaterials()) {
                    for (Milestone.Cost cost : goods.cost()) {
                        if (cost.present() && cost.carried(player.getInventory()) < cost.count()) {
                            player.sendOverlayMessage(Component.translatable("siftec.shop.missing"));
                            return;
                        }
                    }
                    for (Milestone.Cost cost : goods.cost()) if (cost.present()) cost.take(player.getInventory(), cost.count());
                }
                made = new ItemStack(icon(e));
            }
            default -> {
                player.sendOverlayMessage(Component.translatable("siftec.shop.owned"));
                return;
            }
        }
        player.sendOverlayMessage(Component.translatable("siftec.shop.taken", made.getHoverName()));
        player.getInventory().placeItemBackInInventory(made, Prediction.SERVER_ONLY);
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
