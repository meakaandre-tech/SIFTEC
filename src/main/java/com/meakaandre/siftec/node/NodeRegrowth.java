package com.meakaandre.siftec.node;

import com.meakaandre.siftec.block.NodeBlock;
import com.meakaandre.siftec.registry.ModBlocks;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;

/**
 * Nodes are infinite. A node block that goes (mined by hand, drilled, anything that leaves the spot empty) becomes
 * its depleted block at the end of the tick, and that grows back into the node after {@link #ticks}: the purity sets
 * the regrowth, a miner's mark never does. Placing a block over a node (a command, a building) is not mining it, and
 * the node stays gone.
 */
public final class NodeRegrowth {
    private record Removed(ServerLevel level, BlockPos pos, NodeLook look, Purity purity) {
    }

    private static final ArrayDeque<Removed> QUEUE = new ArrayDeque<>();

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

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> drain());
        // the block's own removal hook catches every way a node goes; a player breaking it is caught here as well
        net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
            if (level instanceof ServerLevel server && state.getBlock() instanceof NodeBlock node && !node.look.fluid()) {
                removed(server, pos, node.look, state.getValue(NodeBlock.PURITY));
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> QUEUE.clear());
    }

    /** Called by a node block as it is removed; what is left there is looked at once the tick is over. */
    public static void removed(ServerLevel level, BlockPos pos, NodeLook look, Purity purity) {
        QUEUE.add(new Removed(level, pos.immutable(), look, purity));
    }

    private static void drain() {
        Removed r;
        while ((r = QUEUE.poll()) != null) {
            if (!r.level().isLoaded(r.pos())) continue;
            BlockState now = r.level().getBlockState(r.pos());
            if (now.isAir() || now.canBeReplaced()) deplete(r.level(), r.pos(), r.look(), r.purity());
        }
    }

    /** Puts the depleted node down and sets it to grow back. */
    public static void deplete(ServerLevel level, BlockPos pos, NodeLook look, Purity purity) {
        Block depleted = ModBlocks.DEPLETED.get(look.id()).get();
        level.setBlock(pos, depleted.defaultBlockState().setValue(NodeBlock.PURITY, purity), Block.UPDATE_ALL);
        level.scheduleTick(pos, depleted, ticks(purity));
    }

    public static void regrow(ServerLevel level, BlockPos pos, NodeLook look, Purity purity) {
        level.setBlock(pos, ModBlocks.NODES.get(look.id()).get().defaultBlockState().setValue(NodeBlock.PURITY, purity), Block.UPDATE_ALL);
    }
}
