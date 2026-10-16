package com.meakaandre.siftec.node;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Nodes are infinite. An ore node's position ({@link NodeSites}) whose ore block is gone (mined by hand, drilled by a
 * miner, or taken any other way: blown up, pushed, replaced, a command) is empty, and after {@link #ticks} its ore
 * block grows back. If something has got into the spot meanwhile (anything its ore may not simply replace), it waits
 * until the spot is clear: it never deletes what is there. The purity sets the regrowth; a miner's Mk never does.
 * The time it grows back at is kept with the node in {@link NodeSavedData}, as a game time, so it holds through
 * chunks unloading and server restarts: a node whose chunk is not loaded grows back as soon as it is.
 */
public final class NodeRegrowth {
    private NodeRegrowth() {
    }

    /**
     * Ticks a mined node takes to grow back: one ore at Satisfactory's Mk.1 miner rate for that purity
     * (30, 60 and 120 a minute on impure, normal and pure).
     */
    public static int ticks(Purity purity) {
        return switch (purity) {
            case IMPURE -> 40;
            case NORMAL -> 20;
            case PURE -> 10;
        };
    }

    /** The clock regrowth runs on: the world's game time (the same in every dimension, and saved with the world). */
    public static long now(Level level) {
        MinecraftServer server = level.getServer();
        return server == null ? level.getGameTime() : server.overworld().getGameTime();
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(NodeRegrowth::tick);
        // a node's ore is a plain block: using it with an empty hand still says what node it is
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (hand != InteractionHand.MAIN_HAND || !player.getMainHandItem().isEmpty() || !(player instanceof ServerPlayer)) return InteractionResult.PASS;
            if (NodeSites.isSite(level, hit.getBlockPos())) player.sendOverlayMessage(NodeSites.describe(level, hit.getBlockPos()));
            return InteractionResult.PASS;
        });
    }

    private static void tick(MinecraftServer server) {
        NodeSavedData data = NodeSavedData.get(server);
        long now = server.overworld().getGameTime();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (ServerLevel level : server.getAllLevels()) {
            var sites = data.sites(level.dimension().identifier().toString());
            if (sites.isEmpty()) continue;
            for (Long2ObjectMap.Entry<NodeSavedData.Site> e : sites.long2ObjectEntrySet()) {
                pos.set(e.getLongKey());
                if (!level.isLoaded(pos)) continue;
                NodeSavedData.Site site = e.getValue();
                BlockState state = level.getBlockState(pos);
                if (site.grown()) {
                    // its ore has gone, however: the spot is empty from now and grows back after its purity's time
                    if (!site.look.isNodeBlock(state)) data.setRegrowAt(site, now + ticks(site.purity));
                } else if (now >= site.regrowAt() && regrow(level, pos, site)) {
                    data.setRegrowAt(site, NodeSavedData.GROWN);
                }
            }
        }
        if (server.getTickCount() % 5 == 0) goggles(server);
    }

    /**
     * Puts the ore back when the spot is free: air, or something the ore may simply replace (a fluid, grass, snow). If
     * anything else occupies it (a fallen block, a block a contraption left there, anything with a block entity) the node
     * waits, and grows back as soon as the spot is cleared; nothing in it is ever deleted. True if it grew back.
     */
    static boolean regrow(ServerLevel level, BlockPos pos, NodeSavedData.Site site) {
        if (occupied(level, pos)) return false;
        level.setBlock(pos, site.look.nodeState(), Block.UPDATE_ALL);
        return true;
    }

    /** True if something stands in an empty node's spot that its ore must not replace. */
    public static boolean occupied(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return false;
        return state.hasBlockEntity() || level.getBlockEntity(pos) != null || !state.canBeReplaced();
    }

    private static Item gogglesItem;

    /** A player wearing Create's Engineer's Goggles sees the node they look at: its type, purity and regrowth time. */
    private static void goggles(MinecraftServer server) {
        if (gogglesItem == null) {
            gogglesItem = BuiltInRegistries.ITEM.getOptional(Identifier.fromNamespaceAndPath("create", "goggles")).orElse(net.minecraft.world.item.Items.AIR);
        }
        if (gogglesItem == net.minecraft.world.item.Items.AIR) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!player.getItemBySlot(EquipmentSlot.HEAD).is(gogglesItem)) continue;
            BlockPos node = NodeSites.lookedAt(player, player.blockInteractionRange());
            if (node != null) player.sendOverlayMessage(NodeSites.describe(player.level(), node));
        }
    }
}
