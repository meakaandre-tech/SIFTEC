package com.meakaandre.siftec.block;

import com.meakaandre.siftec.node.Node;
import com.meakaandre.siftec.node.NodeMap;
import com.meakaandre.siftec.node.NodeSites;
import com.meakaandre.siftec.node.NodeType;
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

    /** The node a block at {@code pos} is standing on: it has to be right on the node (a fluid node's well, or an ore node grown or empty). */
    public static Optional<Node> nodeUnder(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel server)) return Optional.empty();
        BlockState below = level.getBlockState(pos.below());
        NodeType type;
        var site = NodeSites.at(level, pos.below());
        if (below.getBlock() instanceof NodeBlock node) type = node.type;
        else if (site != null) type = site.type();
        else return Optional.empty();
        return NodeMap.near(server, pos.getX(), pos.getZ(), 0, type);
    }
}
