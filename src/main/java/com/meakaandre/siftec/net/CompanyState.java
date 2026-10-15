package com.meakaandre.siftec.net;

import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.company.CompanyData;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;

/** Sends players what their company has unlocked, whenever that may have changed. */
public final class CompanyState {
    private CompanyState() {
    }

    /** Sends every player their company's unlocks again. */
    public static void recompute(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) send(player);
    }

    /** Sends a player their company's unlocks. */
    public static void send(ServerPlayer player) {
        Company company = CompanyData.get(player.level().getServer()).ofMember(player.getUUID().toString());
        ServerPlayNetworking.send(player, new StatePayload(company == null ? new ArrayList<>() : new ArrayList<>(company.done)));
        com.meakaandre.siftec.hub.MapLock.update(player, company);
        com.meakaandre.siftec.map.MapServer.companyChanged(player, company);
    }
}
