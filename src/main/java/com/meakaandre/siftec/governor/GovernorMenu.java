package com.meakaandre.siftec.governor;

import net.minecraft.core.component.DataComponents;
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
import net.minecraft.world.item.ItemStack;

/** The Speed Governor's window: a row of speeds to pick from and buttons to nudge it. Run by the server. */
public class GovernorMenu extends ChestMenu {
    private static final int SIZE = 27, STATUS = 13;
    private static final int[] PRESETS = {8, 16, 32, 48, 64, 96, 128, 192, 256};
    /** Slot -> change in RPM, on the middle row. */
    private static final int[][] STEPS = {{10, -16}, {11, -1}, {15, 1}, {16, 16}};
    private final SimpleContainer view;
    private final GovernorBlockEntity governor;

    private GovernorMenu(int id, Inventory inventory, SimpleContainer view, GovernorBlockEntity governor) {
        super(MenuType.GENERIC_9x3, id, inventory, view, 3);
        this.view = view;
        this.governor = governor;
        refresh();
    }

    public static void open(ServerPlayer player, GovernorBlockEntity governor) {
        player.openMenu(new SimpleMenuProvider(
            (id, inventory, p) -> new GovernorMenu(id, inventory, new SimpleContainer(SIZE), governor),
            Component.translatable("block.siftec.speed_governor")));
    }

    private static ItemStack button(String item, Component name) {
        ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(item)));
        stack.set(DataComponents.CUSTOM_NAME, Component.empty().append(name).withStyle(s -> s.withItalic(false)));
        return stack;
    }

    private void refresh() {
        int limit = governor.limit(), target = governor.target();
        for (int i = 0; i < SIZE; i++) view.setItem(i, ItemStack.EMPTY);
        for (int i = 0; i < PRESETS.length; i++) {
            int rpm = PRESETS[i];
            view.setItem(i, rpm > limit ? button("gray_dye", Component.translatable("siftec.governor.over", rpm))
                : button(rpm == target ? "glowstone_dust" : "lime_dye", Component.translatable("siftec.governor.set", rpm)));
        }
        for (int[] step : STEPS) {
            view.setItem(step[0], button(step[1] < 0 ? "redstone" : "emerald", Component.translatable("siftec.governor.step", (step[1] > 0 ? "+" : "") + step[1])));
        }
        view.setItem(STATUS, button("compass", Component.translatable("siftec.governor.status", target, limit)));
    }

    private void press(int slot) {
        int target = governor.target();
        if (slot < PRESETS.length) target = PRESETS[slot];
        for (int[] step : STEPS) if (step[0] == slot) target += step[1];
        governor.setTarget(target);
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
        return !governor.isRemoved();
    }
}
