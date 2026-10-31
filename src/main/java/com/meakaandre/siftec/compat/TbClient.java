package com.meakaandre.siftec.compat;

import com.tiviacz.travelersbackpack.attachment.AttachmentUtils;
import com.tiviacz.travelersbackpack.network.ServerboundActionTagPacket;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;

/**
 * The client's side of the backpack as the inventory: E opens the backpack window instead of the vanilla inventory
 * (mixin/tb/client/TbOpenMixin, on the game's screen switch), and that window draws a panel behind its armour and
 * offhand column ({@link TbEquipSlots}). The vanilla hotbar is the hotbar as always.
 */
public final class TbClient {
    private static final boolean LOADED = FabricLoader.getInstance().isModLoaded("travelersbackpack");

    private TbClient() {
    }

    /**
     * Called when the game is about to show a screen: the vanilla inventory of a survival or adventure player who
     * wears their backpack is swapped for the backpack window. Returns true when it was (the vanilla one is not shown).
     */
    public static boolean openBackpackInstead(Screen screen) {
        if (!LOADED || !(screen instanceof InventoryScreen)) return false;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || player.isSpectator() || mc.getConnection() == null) return false;
        return Tb.open(player);
    }

    /** Only loaded once Traveler's Backpack is known to be there. */
    private static final class Tb {
        static boolean open(LocalPlayer player) {
            if (!AttachmentUtils.isWearingBackpack(player)) return false;
            ServerboundActionTagPacket.create(ServerboundActionTagPacket.OPEN_SCREEN);
            return true;
        }
    }

    /** The panel behind the armour and offhand column, at the window's left edge (left, top: the window's corner). */
    public static void drawEquipment(GuiGraphicsExtractor g, AbstractContainerMenu menu, int left, int top) {
        if (!TbEquipSlots.shown(menu)) return;
        int x = left + TbEquipSlots.PANEL_X, y = top + TbEquipSlots.PANEL_Y, w = TbEquipSlots.PANEL_W, h = TbEquipSlots.PANEL_H;
        // the vanilla window's look: light grey, white top-left edge, dark bottom-right edge, black outline
        g.fill(x, y, x + w, y + h, 0xFF000000);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFFC6C6C6);
        g.fill(x + 1, y + 1, x + w - 2, y + 2, 0xFFFFFFFF);
        g.fill(x + 1, y + 1, x + 2, y + h - 2, 0xFFFFFFFF);
        g.fill(x + 2, y + h - 2, x + w - 1, y + h - 1, 0xFF555555);
        g.fill(x + w - 2, y + 2, x + w - 1, y + h - 1, 0xFF555555);
        for (Slot slot : menu.slots) {
            if (!TbEquipSlots.isEquipSlot(slot)) continue;
            int sx = left + slot.x, sy = top + slot.y;
            g.fill(sx - 1, sy - 1, sx + 17, sy + 17, 0xFF373737);
            g.fill(sx, sy, sx + 17, sy + 17, 0xFFFFFFFF);
            g.fill(sx, sy, sx + 16, sy + 16, 0xFF8B8B8B);
        }
    }
}
