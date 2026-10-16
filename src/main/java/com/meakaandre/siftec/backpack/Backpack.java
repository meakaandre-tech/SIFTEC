package com.meakaandre.siftec.backpack;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;

import java.util.List;
import java.util.function.Function;

/**
 * The player's inventory is the Traveler's Backpack they wear (see
 * {@link com.meakaandre.siftec.compat.TravelersBackpackCompat}). This is the hook through which milestone payments,
 * the Equipment Workshop and radiation see what the worn backpack holds, without the rest of the mod needing that mod.
 */
public final class Backpack {
    /** What the player's worn backpack holds. Set by the compat layer when Traveler's Backpack is installed. */
    public static volatile Function<ServerPlayer, List<Container>> worn = player -> List.of();

    private Backpack() {
    }

    /** The containers of the player's worn backpack: none, or the Traveler's Backpack storage. */
    public static List<Container> containers(ServerPlayer player) {
        return worn.apply(player);
    }
}
