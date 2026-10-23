package com.meakaandre.siftec.compat;

import com.meakaandre.siftec.company.Companies;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Set;

/**
 * The way into The Sift (Mielon's The Sift). The finished Wormhole Gateway sends the player to the mod's
 * dimension, at the same x and z, standing on the highest ground there.
 */
public final class SiftGate {
    public static final ResourceKey<Level> SIFT = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("the_sift", "the_sift"));
    /** Height the player arrives at when The Sift has no ground under them. */
    private static final int NO_GROUND_Y = 100;

    private SiftGate() {
    }

    /** True (with a message to the player) if they may not go to that level: The Sift before their company's Wormhole Phase 5. */
    public static boolean refuses(ServerPlayer player, ServerLevel to) {
        if (to == null || to.dimension() != SIFT || player.level().dimension() == SIFT) return false;
        if (player.hasInfiniteMaterials() || player.isSpectator()) return false;
        if (Companies.of(player).has("phase_5")) return false;
        player.sendOverlayMessage(Component.translatable("siftec.sift.closed"));
        return true;
    }

    private static Block portalBlock;
    private static boolean portalLooked;

    /** True for The Sift's own portal block, which the pack does not let form outside The Sift. */
    public static boolean isPortal(BlockState state) {
        if (!portalLooked) {
            portalBlock = BuiltInRegistries.BLOCK.getOptional(Identifier.fromNamespaceAndPath("the_sift", "sift_portal")).orElse(null);
            portalLooked = true;
        }
        return portalBlock != null && state.getBlock() == portalBlock;
    }

    public static boolean isRift(Entity entity) {
        Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return id.getNamespace().equals("the_sift") && (id.getPath().equals("rift") || id.getPath().equals("mini_rift"));
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
        if (y <= sift.getMinY() + 1) y = NO_GROUND_Y;
        player.teleportTo(sift, x + 0.5, y, z + 0.5, Set.of(), player.getYRot(), player.getXRot(), true);
    }
}
