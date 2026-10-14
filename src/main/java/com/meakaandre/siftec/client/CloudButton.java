package com.meakaandre.siftec.client;

import com.meakaandre.siftec.depot.OpenCloudPayload;
import com.meakaandre.siftec.mixin.client.ContainerScreenAccessor;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;

/** A Cloud button above the inventory screen: opens the company's Dimensional Depot cloud to take items out. */
public final class CloudButton {
    private static final int WIDTH = 44, HEIGHT = 18, PANEL_WIDTH = 176;

    private CloudButton() {
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!(screen instanceof InventoryScreen inventory)) return;
            Button button = Button.builder(Component.translatable("siftec.depot.button"), b -> ClientPlayNetworking.send(new OpenCloudPayload()))
                .bounds(0, 0, WIDTH, HEIGHT).tooltip(Tooltip.create(Component.translatable("siftec.depot.button.tooltip"))).build();
            place(inventory, button);
            Screens.getButtons(screen).add(button);
            // the recipe book moves the panel sideways; the button follows it
            ScreenEvents.afterTick(screen).register(s -> place(inventory, button));
        });
    }

    private static void place(InventoryScreen screen, Button button) {
        ContainerScreenAccessor at = (ContainerScreenAccessor) screen;
        button.setPosition(at.siftec$leftPos() + PANEL_WIDTH - WIDTH, at.siftec$topPos() - HEIGHT - 1);
    }
}
