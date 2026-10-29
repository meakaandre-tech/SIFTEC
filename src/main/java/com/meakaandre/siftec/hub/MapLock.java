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
 * The Map comes with Field Research (Tier 1), as in Satisfactory: until the player's company has done it, SIFTEC's
 * minimap and fullscreen map (MapMod's, through {@code mapapi.SiftecMap}'s unlock provider) stay closed (the compass
 * strip is there from the start). Here the player is told once, when it opens while they play.
 */
public final class MapLock {
    /** The token Field Research gives (tools/tables/content.py). */
    public static final String TOKEN = "map";
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

    /** Tells the player the Map is theirs, if it opened since they were last told. */
    public static void update(ServerPlayer player, @Nullable Company company) {
        boolean on = open(company);
        Boolean before = TOLD.put(player.getUUID(), on);
        // a player joining with the Map already open needs no telling
        if (on && before != null && !before) {
            player.sendSystemMessage(Component.translatable("siftec.map.unlocked").withStyle(ChatFormatting.GOLD));
        }
    }
}
