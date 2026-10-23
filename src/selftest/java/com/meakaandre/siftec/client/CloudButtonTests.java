package com.meakaandre.siftec.client;

import com.meakaandre.siftec.Siftec;
import com.tiviacz.travelersbackpack.client.screens.AbstractBackpackScreen;
import com.tiviacz.travelersbackpack.util.Reference;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.contents.TranslatableContents;

/**
 * The smoke run's {@code tb-client} check: the worn backpack's window, which opens in place of the inventory, has the
 * Cloud button ({@link CloudButton#addBeside}) on screen. Logged once a session, the first time the window opens.
 */
public final class CloudButtonTests {
    private static boolean reported;

    private CloudButtonTests() {
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (reported || !(screen instanceof AbstractBackpackScreen<?> backpack)) return;
            if (backpack.getWrapper() == null || backpack.getWrapper().getScreenID() != Reference.WEARABLE_SCREEN_ID) return;
            for (var child : screen.children()) {
                if (child instanceof Button button && button.getMessage().getContents() instanceof TranslatableContents text
                    && text.getKey().equals("siftec.depot.button")) {
                    reported = true;
                    boolean onScreen = button.getX() >= 0 && button.getY() >= 0 && button.getX() + button.getWidth() <= screen.width
                        && button.getY() + button.getHeight() <= screen.height;
                    Siftec.LOGGER.info("SELFTEST tb-client {} the backpack window has the Cloud button, on screen at {},{} ({}x{} of {}x{})",
                        onScreen ? "PASS" : "FAIL", button.getX(), button.getY(), button.getWidth(), button.getHeight(), screen.width, screen.height);
                    return;
                }
            }
        });
    }
}
