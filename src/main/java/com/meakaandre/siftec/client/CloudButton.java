package com.meakaandre.siftec.client;

import com.meakaandre.siftec.depot.OpenCloudPayload;
import com.meakaandre.siftec.mixin.client.ContainerScreenAccessor;
import com.meakaandre.siftec.mixin.client.ScreenInvoker;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;

/**
 * A Cloud button above the inventory screen: opens the company's Dimensional Depot cloud to take items out. The
 * backpack window, which opens in place of the inventory, has it too (added by mixin/tb/client/TbScreenMixin through
 * {@link #add}).
 */
public final class CloudButton {
    private static final int WIDTH = 44, HEIGHT = 18, PANEL_WIDTH = 176;

    private CloudButton() {
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!(screen instanceof InventoryScreen inventory)) return;
            Button button = add(inventory, PANEL_WIDTH);
            // the recipe book moves the panel sideways; the button follows it
            ScreenEvents.afterTick(screen).register(s -> place(inventory, button, PANEL_WIDTH));
        });
    }

    /** Puts a Cloud button on the screen, above the right end of its panel (panelWidth wide), and returns it. */
    public static Button add(AbstractContainerScreen<?> screen, int panelWidth) {
        Button button = Button.builder(Component.translatable("siftec.depot.button"), b -> ClientPlayNetworking.send(new OpenCloudPayload()))
            .bounds(0, 0, WIDTH, HEIGHT).tooltip(Tooltip.create(Component.translatable("siftec.depot.button.tooltip"))).build();
        place(screen, button, panelWidth);
        ((ScreenInvoker) screen).siftec$addRenderableWidget(button);
        return button;
    }

    /**
     * The backpack window's button: above the right end of the window, or, when the window is too tall to leave room
     * above it, just right of its bottom corner.
     */
    public static Button addBeside(AbstractContainerScreen<?> screen, int panelWidth, int panelHeight) {
        Button button = add(screen, panelWidth);
        ContainerScreenAccessor at = (ContainerScreenAccessor) screen;
        if (button.getY() < 0) button.setPosition(at.siftec$leftPos() + panelWidth + 2, at.siftec$topPos() + panelHeight - HEIGHT);
        return button;
    }

    private static void place(AbstractContainerScreen<?> screen, Button button, int panelWidth) {
        ContainerScreenAccessor at = (ContainerScreenAccessor) screen;
        button.setPosition(at.siftec$leftPos() + panelWidth - WIDTH, at.siftec$topPos() - HEIGHT - 1);
    }
}
