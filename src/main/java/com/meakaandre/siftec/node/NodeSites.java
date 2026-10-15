package com.meakaandre.siftec.node;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.Locale;

/**
 * Where the ore nodes are. An ore node's block is the real ore block (minecraft:iron_ore, create:zinc_ore, the stone of
 * a stone node, a SIFTEC ore where the game has none), with nothing on it to say it is a node: a position is a node
 * because it is in {@link NodeSavedData}'s list, with its type, look and purity. Mined (by hand, a drill, a miner),
 * blown, pushed, replaced or set by a command, the spot is empty until it grows back into the same block
 * ({@link NodeRegrowth}); while it is empty nothing can be placed or poured into it, and nothing can move or blow up
 * the ore while it stands. Fluid nodes (oil, nitrogen) are their own unbreakable blocks and are not in this list.
 */
public final class NodeSites {
    private NodeSites() {
    }

    private static String dim(Level level) {
        return level.dimension().identifier().toString();
    }

    /** The ore node at this position, or null (always null on the client: only the server knows the nodes). */
    public static NodeSavedData.@Nullable Site at(@Nullable Level level, @Nullable BlockPos pos) {
        if (!(level instanceof ServerLevel server) || pos == null) return null;
        return NodeSavedData.get(server.getServer()).site(dim(level), pos.asLong());
    }

    public static boolean isSite(@Nullable Level level, @Nullable BlockPos pos) {
        return at(level, pos) != null;
    }

    /** True if this position is an ore node and its ore block stands there now. */
    public static boolean grownAt(Level level, BlockPos pos) {
        NodeSavedData.Site site = at(level, pos);
        return site != null && site.look.isNodeBlock(level.getBlockState(pos));
    }

    /** True if this position is an ore node and its ore is not there (mined, or anything else in its place). */
    public static boolean emptyAt(Level level, BlockPos pos) {
        NodeSavedData.Site site = at(level, pos);
        return site != null && !site.look.isNodeBlock(level.getBlockState(pos));
    }

    /**
     * Makes a position an ore node, if it is not one already (a new site, or a site placed before nodes were kept by
     * position: whatever stands there, it grows back into its ore). Returns the site.
     */
    public static NodeSavedData.Site ensure(ServerLevel level, BlockPos pos, NodeLook look, Purity purity) {
        NodeSavedData data = NodeSavedData.get(level.getServer());
        NodeSavedData.Site site = data.site(dim(level), pos.asLong());
        if (site != null && site.look.equals(look) && site.purity == purity) return site;
        return data.putSite(dim(level), pos.asLong(), look, purity, NodeSavedData.GROWN);
    }

    /** Puts an ore node here, its ore grown (the selftests' own nodes, and anything that builds a site by hand). */
    public static NodeSavedData.Site put(ServerLevel level, BlockPos pos, NodeLook look, Purity purity) {
        level.setBlock(pos, look.nodeState(), Block.UPDATE_ALL);
        return NodeSavedData.get(level.getServer()).putSite(dim(level), pos.asLong(), look, purity, NodeSavedData.GROWN);
    }

    /** Forgets the node at this position (the selftests clearing their own). */
    public static void remove(ServerLevel level, BlockPos pos) {
        NodeSavedData.get(level.getServer()).removeSite(dim(level), pos.asLong());
    }

    /** Holds an empty node empty until {@link #release}d (the restart selftest: as a node whose chunk stays unloaded waits). */
    public static void hold(ServerLevel level, BlockPos pos) {
        NodeSavedData.Site site = at(level, pos);
        if (site != null) NodeSavedData.get(level.getServer()).setRegrowAt(site, Long.MAX_VALUE / 2);
    }

    /** Lets a held node grow back, its purity's time from now. */
    public static void release(ServerLevel level, BlockPos pos) {
        NodeSavedData.Site site = at(level, pos);
        if (site != null) NodeSavedData.get(level.getServer()).setRegrowAt(site, NodeRegrowth.now(level) + NodeRegrowth.ticks(site.purity));
    }

    /** Ticks until the empty node here grows back: 0 if it is grown (or due now), -1 if there is no node here. */
    public static long ticksLeft(Level level, BlockPos pos) {
        NodeSavedData.Site site = at(level, pos);
        if (site == null) return -1;
        if (site.grown()) return 0;
        return Math.max(0, site.regrowAt() - NodeRegrowth.now(level));
    }

    /** "Iron Ore, Pure" and, while it is empty, how long until it grows back. */
    public static Component describe(Level level, BlockPos pos) {
        NodeSavedData.Site site = at(level, pos);
        if (site == null) return Component.empty();
        Component label = com.meakaandre.siftec.block.NodeBlock.label(site.type(), site.purity);
        if (site.look.isNodeBlock(level.getBlockState(pos))) return label;
        long left = Math.max(0, site.grown() ? 0 : site.regrowAt() - NodeRegrowth.now(level));
        return Component.translatable("siftec.node.regrowing", label, String.format(Locale.ROOT, "%.1f", left / 20.0));
    }

    /**
     * The ore node a player is looking at, within {@code reach} blocks: the first node position along their line of
     * sight, whether its ore stands there or the spot is empty air. Stops at the first other solid block.
     */
    public static @Nullable BlockPos lookedAt(Player player, double reach) {
        Level level = player.level();
        Vec3 eye = player.getEyePosition();
        Vec3 dir = player.getViewVector(1f);
        BlockPos last = null;
        for (double t = 0; t <= reach; t += 0.05) {
            BlockPos pos = BlockPos.containing(eye.add(dir.scale(t)));
            if (pos.equals(last)) continue;
            last = pos;
            if (isSite(level, pos)) return pos;
            BlockState state = level.getBlockState(pos);
            if (!state.isAir() && !state.getCollisionShape(level, pos).isEmpty()) return null;
        }
        return null;
    }
}
