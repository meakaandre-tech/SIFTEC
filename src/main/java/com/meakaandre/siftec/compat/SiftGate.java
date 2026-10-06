package com.meakaandre.siftec.compat;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Set;

/**
 * The way into The Sift (Mielon's The Sift). The finished Wormhole Gateway sends the player to the mod's
 * dimension, at the same x and z, standing on the highest ground there.
 */
public final class SiftGate {
    public static final ResourceKey<Level> SIFT = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("the_sift", "the_sift"));

    private SiftGate() {
    }

    /** True (with a message to the player) if they may not go to that level: The Sift before their company's Wormhole Phase 5. */
    public static boolean refuses(ServerPlayer player, ServerLevel to) {
        if (to == null || to.dimension() != SIFT || player.level().dimension() == SIFT) return false;
        if (player.hasInfiniteMaterials() || player.isSpectator()) return false;
        if (com.meakaandre.siftec.company.Companies.of(player).has("phase_5")) return false;
        player.sendOverlayMessage(Component.translatable("siftec.sift.closed"));
        return true;
    }

    public static void enter(ServerPlayer player) {
        ServerLevel sift = player.level().getServer().getLevel(SIFT);
        if (sift == null) {
            player.sendOverlayMessage(Component.translatable("siftec.sift.missing"));
            return;
        }
        int x = player.getBlockX(), z = player.getBlockZ();
        sift.getChunk(x >> 4, z >> 4);
        int y = sift.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) + 1;
        if (y <= sift.getMinY() + 1) y = 100;
        player.teleportTo(sift, x + 0.5, y, z + 0.5, Set.of(), player.getYRot(), player.getXRot(), true);
    }
}
