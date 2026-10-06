package com.meakaandre.siftec.mam;

import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.hub.HubMenu;
import com.meakaandre.siftec.hub.Milestone;
import com.meakaandre.siftec.hub.Milestones;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
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
 * The MAM window, run by the server like the HUB's. The top row picks a tree, the middle row lists its
 * nodes. Clicking a node delivers parts; once it is paid for, its research time starts. One node at a time.
 */
public class MamMenu extends ChestMenu {
    private static final int SIZE = 54, STATUS_SLOT = 17, FIRST_NODE = 28;
    private final SimpleContainer view;
    private final ServerPlayer player;
    private final Company company;
    private int tree;
    private int ticks;

    private MamMenu(int id, Inventory inventory, SimpleContainer view, ServerPlayer player, Company company) {
        super(MenuType.GENERIC_9x6, id, inventory, view, 6);
        this.view = view;
        this.player = player;
        this.company = company;
        refresh();
    }

    public static void open(ServerPlayer player, Company company) {
        player.openMenu(new SimpleMenuProvider(
            (id, inventory, p) -> new MamMenu(id, inventory, new SimpleContainer(SIZE), player, company),
            Component.translatable("siftec.mam.title", company.name)));
    }

    private long now() {
        return player.level().getServer().overworld().getGameTime();
    }

    private static String clock(long ticks) {
        long seconds = (Math.max(0, ticks) + 19) / 20;
        return seconds / 60 + ":" + (seconds % 60 < 10 ? "0" : "") + seconds % 60;
    }

    private static ItemStack button(Item icon, Component name, List<Component> lore, boolean glint) {
        ItemStack stack = new ItemStack(icon);
        stack.set(DataComponents.CUSTOM_NAME, Component.empty().append(name).withStyle(s -> s.withItalic(false)));
        if (!lore.isEmpty()) {
            List<Component> lines = new ArrayList<>();
            for (Component line : lore) lines.add(Component.empty().append(line).withStyle(s -> s.withItalic(false)));
            stack.set(DataComponents.LORE, new ItemLore(lines));
        }
        if (glint) stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        return stack;
    }

    private static Item item(String id) {
        return BuiltInRegistries.ITEM.getOptional(Identifier.parse(id)).orElse(Items.BOOK);
    }

    private void refresh() {
        for (int i = 0; i < SIZE; i++) view.setItem(i, ItemStack.EMPTY);
        List<Milestones.Tree> trees = Milestones.trees();
        for (int t = 0; t < trees.size() && t < 9; t++) {
            Item icon = BuiltInRegistries.ITEM.getOptional(trees.get(t).icon()).orElse(Items.BOOK);
            view.setItem(t, button(icon, Component.translatable("siftec.tree." + trees.get(t).id()), List.of(), t == tree));
        }
        Milestone running = Milestones.get(company.research);
        view.setItem(STATUS_SLOT, button(Items.CLOCK, running == null
            ? Component.translatable("siftec.mam.idle").withStyle(ChatFormatting.GREEN)
            : Component.translatable("siftec.mam.running", running.name(), clock(company.researchEnd - now())).withStyle(ChatFormatting.GOLD), List.of(), false));
        if (tree >= trees.size()) return;
        List<Milestone> nodes = trees.get(tree).nodes();
        for (int i = 0; i < nodes.size() && i < 7; i++) view.setItem(FIRST_NODE + i, nodeButton(nodes.get(i)));
    }

    private ItemStack nodeButton(Milestone m) {
        List<Component> lore = new ArrayList<>();
        boolean done = company.has(m.id()), researching = company.research.equals(m.id());
        Milestone blocker = done ? null : Milestones.blocker(company, m);
        if (done) {
            lore.add(Component.translatable("siftec.hub.done").withStyle(ChatFormatting.GREEN));
        } else if (researching) {
            lore.add(Component.translatable("siftec.mam.running", m.name(), clock(company.researchEnd - now())).withStyle(ChatFormatting.GOLD));
        } else {
            for (Milestone.Cost cost : m.cost()) {
                if (!cost.present()) continue;
                int need = company.cost(cost), have = company.paid(m, cost);
                lore.add(Component.translatable("siftec.hub.cost", cost.label(), have, need).withStyle(have >= need ? ChatFormatting.GREEN : ChatFormatting.WHITE));
            }
            lore.add(Component.translatable("siftec.mam.research", clock(m.seconds() * 20L)).withStyle(ChatFormatting.GRAY));
        }
        lore.add(Component.translatable("siftec.hub.unlocks", m.unlockText()).withStyle(ChatFormatting.AQUA));
        if (blocker != null) lore.add(Component.translatable("siftec.hub.needs", blocker.name()).withStyle(ChatFormatting.RED));
        else if (!done && !researching) lore.add(Component.translatable("siftec.mam.click").withStyle(ChatFormatting.YELLOW));
        Item icon = done ? item("minecraft:lime_dye") : researching ? Items.CLOCK : blocker != null ? Items.BARRIER : Items.PAPER;
        return button(icon, m.name(), lore, researching);
    }

    private void press(int slot) {
        List<Milestones.Tree> trees = Milestones.trees();
        if (slot < trees.size() && slot < 9) {
            tree = slot;
            refresh();
            return;
        }
        int index = slot - FIRST_NODE;
        if (tree >= trees.size() || index < 0 || index >= trees.get(tree).nodes().size()) return;
        Milestone m = trees.get(tree).nodes().get(index);
        MinecraftServer server = player.level().getServer();
        if (company.has(m.id()) || company.research.equals(m.id())) return;
        Milestone blocker = Milestones.blocker(company, m);
        if (blocker != null) {
            player.sendOverlayMessage(Component.translatable("siftec.hub.needs", blocker.name()));
            return;
        }
        int delivered = HubMenu.deliver(player, company, m);
        if (company.fullyPaid(m)) {
            Milestone running = Milestones.get(company.research);
            if (running != null) {
                player.sendOverlayMessage(Component.translatable("siftec.mam.busy", running.name()));
            } else {
                company.research = m.id();
                company.researchEnd = now() + m.seconds() * 20L;
                Companies.tell(server, company, Component.translatable("siftec.mam.started", m.name()).withStyle(ChatFormatting.GOLD));
            }
        } else {
            player.sendOverlayMessage(delivered > 0 ? Component.translatable("siftec.hub.delivered", delivered) : Component.translatable("siftec.hub.nothing"));
        }
        Companies.save(server);
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
