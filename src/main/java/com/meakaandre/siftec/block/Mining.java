package com.meakaandre.siftec.block;

import com.meakaandre.siftec.node.Node;
import com.meakaandre.siftec.node.NodeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Optional;

/** The node under a block standing on one (the Resource Well Extractor's). Miners really mine their node: see building.BuildingLogic. */
public final class Mining {
    private Mining() {
    }

    /** The node a miner at {@code pos} is standing on: it has to be right on the node block (grown or mined). */
    public static Optional<Node> nodeUnder(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel server)) return Optional.empty();
        BlockState below = level.getBlockState(pos.below());
        com.meakaandre.siftec.node.NodeType type;
        if (below.getBlock() instanceof NodeBlock node) type = node.type;
        else if (below.getBlock() instanceof DepletedNodeBlock depleted) type = depleted.look.type();
        else return Optional.empty();
        return NodeMap.near(server, pos.getX(), pos.getZ(), 0, type);
    }
}
