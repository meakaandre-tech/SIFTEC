package com.meakaandre.siftec.tweak;

import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.company.CompanyData;
import com.meakaandre.siftec.net.StatePayload;
import com.meakaandre.siftec.owner.Ownership;
import com.zurrtum.create.content.kinetics.base.KineticBlockEntity;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;

/**
 * The rotation speed limit, per company. Every kinetic block answers to the company it belongs to: turning
 * it faster than that company's limit breaks it, the way Create breaks anything pushed past 256 RPM. The
 * limit starts at 32 RPM and each Logistics milestone raises it.
 */
public final class SpeedCap {
    public static final int START = 32, MAX = 256;

    private SpeedCap() {
    }

    public static int of(@Nullable Company company) {
        return company == null ? START : Math.min(MAX, company.best("cap:", START));
    }

    public static int of(KineticBlockEntity be) {
        return of(Ownership.of(be));
    }

    /**
     * Create's check while it spreads rotation from one block to the next. A block nobody owns goes by the
     * limit of the block it is joined to.
     */
    public static boolean tooFast(KineticBlockEntity current, KineticBlockEntity neighbour, float toNeighbour, float toCurrent) {
        if (!(current.getLevel() instanceof ServerLevel)) return false;
        float a = Math.abs(toNeighbour), b = Math.abs(toCurrent);
        if (a <= START && b <= START) return false;
        Company mine = Ownership.of(current), theirs = Ownership.of(neighbour);
        if (mine == null) mine = theirs;
        if (theirs == null) theirs = mine;
        return a > of(theirs) || b > of(mine);
    }

    /** Sends every player their company's limit and unlocks again. */
    public static void recompute(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) send(player);
    }

    /** Sends a player their company's limit and unlocks. */
    public static void send(ServerPlayer player) {
        Company company = CompanyData.get(player.level().getServer()).ofMember(player.getUUID().toString());
        ServerPlayNetworking.send(player, new StatePayload(of(company), company == null ? new ArrayList<>() : new ArrayList<>(company.done)));
    }
}
