package com.meakaandre.siftec.block;

import net.minecraft.world.level.block.Block;

/**
 * The flat pad a node stands on, and the fill under it: one block per node type, a plain block of that node's natural
 * stone (siftec:node_pad and siftec:node_pad_fill are the old pads', kept so old sites still load). Like the node itself it cannot be broken, blown up,
 * pushed, or moved by a contraction, and Create's drills skip it (its hardness is -1; it is also in create:non_movable).
 */
public class NodePadBlock extends Block {
    public NodePadBlock(Properties properties) {
        super(properties);
    }
}
