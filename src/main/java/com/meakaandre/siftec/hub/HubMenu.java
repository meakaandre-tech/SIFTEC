package com.meakaandre.siftec.hub;

import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.Company;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
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
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.List;

/**
 * The HUB and Wormhole Gateway screen. It is an ordinary six-row chest window run entirely by the server:
 * every slot is a button. Top rows pick the tier, the middle row lists that tier's milestones, and
 * clicking a milestone delivers the parts you are carrying.
 */
public class HubMenu extends ChestMenu {
    private static final int SIZE = 54;
    private static final int INFO_SLOT = 13, LOCK_SLOT = 17, FIRST_MILESTONE = 28;

    private final SimpleContainer view;
    private final ServerPlayer player;
    private final Company company;
    private final boolean gateway;
    private int tier;
    private int ticks;

    private HubMenu(int id, Inventory inventory, SimpleContainer view, ServerPlayer player, Company company, boolean gateway) {
        super(MenuType.GENERIC_9x6, id, inventory, view, 6);
        this.view = view;
        this.player = player;
        this.company = company;
        this.gateway = gateway;
        this.tier = firstUnfinishedTier();
        refresh();
    }

    public static void open(ServerPlayer player, Company company, boolean gateway) {
        Component title = Component.translatable(gateway ? "siftec.gateway.title" : "siftec.hub.title", company.name);
        player.openMenu(new SimpleMenuProvider(
            (id, inventory, p) -> new HubMenu(id, inventory, new SimpleContainer(SIZE), player, company, gateway), title));
    }

    private int firstUnfinishedTier() {
        for (int t = 0; t < Milestones.TIERS; t++) {
            if (!Milestones.tierOpen(company, t)) break;
            for (Milestone m : Milestones.tier(t)) if (!company.has(m.id())) return t;
        }
        return 0;
    }

    private List<Milestone> shown() {
        return gateway ? Milestones.phases() : Milestones.tier(tier);
    }

    private long lockTicks() {
        return Math.max(0, company.lockUntil - player.level().getServer().overworld().getGameTime());
    }

    private static String clock(long ticks) {
        long seconds = (ticks + 19) / 20;
        return seconds / 60 + ":" + (seconds % 60 < 10 ? "0" : "") + seconds % 60;
    }

    private void refresh() {
        for (int i = 0; i < SIZE; i++) view.setItem(i, ItemStack.EMPTY);
        if (!gateway) {
            for (int t = 0; t < Milestones.TIERS; t++) view.setItem(t, tierTab(t));
            long lock = lockTicks();
            view.setItem(LOCK_SLOT, button(Items.CLOCK, lock > 0
                ? Component.translatable("siftec.hub.locked_for", clock(lock)).withStyle(ChatFormatting.RED)
                : Component.translatable("siftec.hub.ready").withStyle(ChatFormatting.GREEN), List.of(), false));
        }
        view.setItem(INFO_SLOT, button(Items.NAME_TAG, Component.translatable(
            "siftec.hub.company", company.name, company.members.size(), company.costMultiplier()), List.of(), false));
        List<Milestone> list = shown();
        for (int i = 0; i < list.size() && i < 7; i++) view.setItem(FIRST_MILESTONE + i, milestoneButton(list.get(i)));
    }

    private ItemStack tierTab(int t) {
        boolean open = Milestones.tierOpen(company, t);
        boolean all = true;
        for (Milestone m : Milestones.tier(t)) all &= company.has(m.id());
        Item icon = !open ? icon("gray_stained_glass_pane") : all ? icon("lime_stained_glass_pane") : icon("yellow_stained_glass_pane");
        List<Component> lore = new ArrayList<>();
        if (!open) {
            int phase = Milestones.phaseFor(t);
            lore.add(phase == 0 || !company.has("hub_upgrade_6")
                ? Component.translatable("siftec.hub.tier.locked0")
                : Component.translatable("siftec.hub.tier.locked", Milestones.phases().get(phase - 1).name()));
        }
        return button(icon, Component.translatable("siftec.hub.tier", t), lore, t == tier);
    }

    private ItemStack milestoneButton(Milestone m) {
        List<Component> lore = new ArrayList<>();
        boolean done = company.has(m.id());
        Milestone blocker = done ? null : Milestones.blocker(company, m);
        if (done) {
            lore.add(Component.translatable("siftec.hub.done").withStyle(ChatFormatting.GREEN));
        } else {
            for (Milestone.Cost cost : m.cost()) {
                Item item = cost.item();
                if (item == Items.AIR) continue;
                int need = company.cost(cost), have = company.paid(m, cost);
                lore.add(Component.translatable("siftec.hub.cost", new ItemStack(item).getItemName(), have, need)
                    .withStyle(have >= need ? ChatFormatting.GREEN : ChatFormatting.WHITE));
            }
            if (m.seconds() > 0) lore.add(Component.translatable("siftec.hub.time", clock(m.seconds() * 20L)).withStyle(ChatFormatting.GRAY));
        }
        lore.add(Component.translatable("siftec.hub.unlocks", m.unlockText()).withStyle(ChatFormatting.AQUA));
        if (blocker != null) {
            lore.add(Component.translatable("siftec.hub.needs", blocker.name()).withStyle(ChatFormatting.RED));
        } else if (!done) {
            lore.add(Component.translatable("siftec.hub.click").withStyle(ChatFormatting.YELLOW));
        }
        Item icon = done ? icon("lime_dye") : blocker != null ? Items.BARRIER : Items.PAPER;
        return button(icon, m.name(), lore, !done && company.active.equals(m.id()));
    }

    private static Item icon(String id) {
        return BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(id));
    }

    private static ItemStack button(Item icon, Component name, List<Component> lore, boolean glint) {
        ItemStack stack = new ItemStack(icon);
        MutableComponent plain = Component.empty().append(name).withStyle(style -> style.withItalic(false));
        stack.set(DataComponents.CUSTOM_NAME, plain);
        if (!lore.isEmpty()) {
            List<Component> lines = new ArrayList<>();
            for (Component line : lore) lines.add(Component.empty().append(line).withStyle(style -> style.withItalic(false)));
            stack.set(DataComponents.LORE, new ItemLore(lines));
        }
        if (glint) stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        return stack;
    }

    private void press(int slot) {
        if (!gateway && slot < Milestones.TIERS) {
            tier = slot;
            refresh();
            return;
        }
        int index = slot - FIRST_MILESTONE;
        List<Milestone> list = shown();
        if (index < 0 || index >= list.size()) return;
        Milestone m = list.get(index);
        MinecraftServer server = player.level().getServer();
        if (company.has(m.id())) return;
        Milestone blocker = Milestones.blocker(company, m);
        if (blocker != null) {
            player.sendOverlayMessage(Component.translatable("siftec.hub.needs", blocker.name()));
            return;
        }
        if (!m.isPhase() && lockTicks() > 0) {
            player.sendOverlayMessage(Component.translatable("siftec.hub.busy", clock(lockTicks())));
            return;
        }
        company.active = m.id();
        int delivered = deliver(m);
        Companies.save(server);
        if (company.fullyPaid(m)) {
            Companies.complete(server, company, m, player.getName());
        } else {
            player.sendOverlayMessage(delivered > 0
                ? Component.translatable("siftec.hub.delivered", delivered)
                : Component.translatable("siftec.hub.nothing"));
        }
        refresh();
    }

    /** Takes what the milestone still needs out of the player's inventory. */
    private int deliver(Milestone m) {
        Inventory inventory = player.getInventory();
        int total = 0;
        for (Milestone.Cost cost : m.cost()) {
            Item item = cost.item();
            if (item == Items.AIR) continue;
            int need = company.needed(m, cost);
            for (int i = 0; i < inventory.getContainerSize() && need > 0; i++) {
                ItemStack stack = inventory.getItem(i);
                if (!stack.is(item)) continue;
                int take = Math.min(need, stack.getCount());
                inventory.removeItem(i, take);
                company.pay(m, cost, take);
                need -= take;
                total += take;
            }
        }
        return total;
    }

    @Override
    public void clicked(int slot, int button, ContainerInput input, Player who) {
        if (slot >= 0 && slot < SIZE) {
            if (input == ContainerInput.PICKUP || input == ContainerInput.QUICK_MOVE) press(slot);
            return;
        }
        // the player's own inventory still works, but nothing may be moved into the buttons
        if (input == ContainerInput.PICKUP || input == ContainerInput.THROW) super.clicked(slot, button, input, who);
    }

    @Override
    public ItemStack quickMoveStack(Player who, int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public void broadcastChanges() {
        // the lock timer counts down while the window is open
        if (++ticks % 20 == 0) refresh();
        super.broadcastChanges();
    }

    @Override
    public boolean stillValid(Player who) {
        return true;
    }
}
