package com.meakaandre.siftec.client;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.compat.TravelersBackpackSelfTest;
import com.tiviacz.travelersbackpack.client.screens.AbstractBackpackScreen;
import com.tiviacz.travelersbackpack.util.Reference;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.player.LocalPlayer;

/**
 * The smoke run's check of what E opened ({@code /siftec selftest tb inventory}, sent after the script pressed E): a
 * creative player gets the vanilla creative inventory (item tabs, search, the survival inventory tab), never the
 * backpack window; a survival player gets the backpack window. Logs "SELFTEST creative-client PASS/FAIL ..." and, on
 * {@code inventory done}, "SELFTEST creative-client done: N passed, M failed".
 */
public final class CreativeInventoryClientTests {
    private static int passed, failed;

    private CreativeInventoryClientTests() {
    }

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(TravelersBackpackSelfTest.InventoryCheck.TYPE,
            (payload, context) -> Minecraft.getInstance().execute(() -> check(payload.done())));
    }

    private static void check(boolean done) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (done) {
            Siftec.LOGGER.info("SELFTEST creative-client done: {} passed, {} failed", passed, failed);
            return;
        }
        Screen screen = mc.gui.screen();
        String shown = screen == null ? "no screen" : screen.getClass().getName();
        if (player == null) {
            expect("a player is in the world", false, shown);
        } else if (player.hasInfiniteMaterials()) {
            expect("in creative E opens the vanilla creative inventory, not the backpack window",
                screen instanceof CreativeModeInventoryScreen && player.containerMenu == player.inventoryMenu, shown);
        } else {
            boolean backpack = screen instanceof AbstractBackpackScreen<?> window && window.getWrapper() != null
                && window.getWrapper().getScreenID() == Reference.WEARABLE_SCREEN_ID;
            expect("in survival E still opens the backpack window", backpack, shown);
        }
    }

    private static void expect(String what, boolean ok, String detail) {
        if (ok) passed++;
        else failed++;
        Siftec.LOGGER.info("SELFTEST creative-client {} {} ({})", ok ? "PASS" : "FAIL", what, detail);
    }
}
