package com.meakaandre.siftec.hub;

import com.meakaandre.siftec.company.Company;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Map comes with Field Research (Tier 1), as in Satisfactory: until the player's company has done it, Xaero's
 * Minimap is switched off for them. Xaero's mods read codes servers put in system messages (its FAQ for server
 * owners): one switches the minimap off, the other resets every such switch. Each player is sent the one that fits
 * when they join and whenever it changes; with Xaero's mods not installed the codes show as nothing.
 */
public final class MapLock {
    /** Xaero's "disable the minimap completely" code. */
    public static final String NO_MINIMAP = "\u00a7n\u00a7o\u00a7m\u00a7i\u00a7n\u00a7i\u00a7m\u00a7a\u00a7p";
    /** Xaero's "reset all system message flags" code. */
    public static final String RESET = "\u00a7r\u00a7e\u00a7s\u00a7e\u00a7t\u00a7x\u00a7a\u00a7e\u00a7r\u00a7o";
    /** The token Field Research gives (tools/content.py). */
    public static final String TOKEN = "minimap";
    /** What each player online was last told: true for the Map on. */
    private static final Map<UUID, Boolean> TOLD = new ConcurrentHashMap<>();

    private MapLock() {
    }

    public static void register() {
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> TOLD.remove(handler.getPlayer().getUUID()));
    }

    /** True if the company's players have the Map. */
    public static boolean open(@Nullable Company company) {
        return company != null && company.hasToken(TOKEN);
    }

    /** Tells the player's Xaero's Minimap whether it may show, if that has changed since it was last told. */
    public static void update(ServerPlayer player, @Nullable Company company) {
        boolean on = open(company);
        Boolean before = TOLD.put(player.getUUID(), on);
        if (before != null && before == on) return;
        if (on) {
            // a player joining starts with the minimap on: only a change while they play needs saying
            if (before != null) player.sendSystemMessage(Component.translatable("siftec.map.unlocked").withStyle(ChatFormatting.GOLD).append(Component.literal(RESET)));
        } else {
            player.sendSystemMessage(Component.literal(NO_MINIMAP));
        }
    }
}
