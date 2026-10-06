package com.meakaandre.siftec.tweak;

import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.company.CompanyData;
import com.meakaandre.siftec.net.StatePayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;

/**
 * The rotation speed cap. It replaces Create's own "maximum rotation speed" setting, so going over it does
 * what Create always does at its limit: the block breaks. It starts at 32 RPM and each Logistics milestone
 * raises it. The cap is world-wide: it follows the furthest company.
 */
public final class SpeedCap {
    public static final int START = 32;
    /** Create's own limit while no world is running. */
    public static volatile int value = 256;

    private SpeedCap() {
    }

    public static void recompute(MinecraftServer server) {
        int cap = START;
        for (Company company : CompanyData.get(server).companies().values()) cap = Math.max(cap, company.best("cap:", START));
        value = cap;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) send(player);
    }

    /** Sends a player the cap and their company's unlocks. */
    public static void send(ServerPlayer player) {
        Company company = CompanyData.get(player.level().getServer()).ofMember(player.getUUID().toString());
        ServerPlayNetworking.send(player, new StatePayload(value, company == null ? new ArrayList<>() : new ArrayList<>(company.done)));
    }
}
