package com.meakaandre.siftec.building;

import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.util.Chunks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

import java.util.function.Predicate;

/**
 * What keeps a building's window (the Sink, the Shop, the Equipment Workshop, the MAM) open, as the Depot's cloud does
 * ({@link com.meakaandre.siftec.depot.DepotMenu#valid}): the player is alive and within reach of the building, the
 * building still stands, and, for a window that works for a company, the player is still one of its members. Windows
 * keep only their company's id and look the company up on every click ({@link #company}), so a company folded into
 * another pays into the one that took it over.
 */
public final class BuildingMenus {
    /** Blocks a player can be from the building they opened a window at and keep it open. */
    public static final double REACH = 8.0;

    private BuildingMenus() {
    }

    /** True if the player is still in the world and alive. */
    public static boolean alive(Player player) {
        return !player.isRemoved() && player.isAlive();
    }

    /** True if the player is within {@code reach} blocks of the middle of the block at {@code at}. */
    public static boolean withinReach(Player player, BlockPos at, double reach) {
        return player.distanceToSqr(at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5) <= reach * reach;
    }

    /** True if the player is alive and within {@link #REACH} of the block entity at {@code at}, which {@code present} accepts. */
    public static boolean near(ServerPlayer player, BlockPos at, Predicate<BlockEntity> present) {
        if (!alive(player)) return false;
        BlockEntity be = Chunks.blockEntity(player.level(), at);
        if (be == null || !present.test(be)) return false;
        AABB box = be instanceof ControllerBlockEntity c ? BuildGun.bounds(c) : new AABB(at);
        return box.distanceToSqr(player.position()) <= REACH * REACH;
    }

    /** True if the player is (still) a member of the company {@code companyId} names (through a fold too). */
    public static boolean member(ServerPlayer player, String companyId) {
        Company now = Companies.find(player);
        return now != null && Companies.same(now.id, companyId);
    }

    /** Accepts a building controller whose building runs {@code logic} ("sink", "shop", "workshop"). */
    public static Predicate<BlockEntity> logic(String logic) {
        return be -> {
            if (!(be instanceof ControllerBlockEntity c)) return false;
            BuildingDef def = Buildings.def(c.buildingId);
            return def != null && logic.equals(def.logic());
        };
    }

    /** A building's window stays open: {@code who} is the {@code player} it was opened for, {@link #near} and a {@link #member}. */
    public static boolean stillValid(Player who, ServerPlayer player, BlockPos at, Predicate<BlockEntity> present,
                                     String companyId) {
        return who == player && near(player, at, present) && member(player, companyId);
    }

    /** The building at {@code at} still stands as {@code buildingId}, within reach, and the player is still in {@code companyId}. */
    public static boolean valid(ServerPlayer player, BlockPos at, String buildingId, String companyId) {
        return near(player, at, be -> be instanceof ControllerBlockEntity c && c.buildingId.equals(buildingId)) && member(player, companyId);
    }

    /** The company with this id today (following folds), or null once it is gone. */
    public static @Nullable Company company(ServerPlayer player, String companyId) {
        return companyId.isEmpty() ? null : Companies.byId(player.level().getServer(), companyId);
    }
}
