package com.meakaandre.siftec.block;

import net.minecraft.world.level.block.Block;

/**
 * A node's patch: the flat pad it stands on and the fill under it, one block per patch stone ({@code <stone>_patch},
 * registered in {@code ModBlocks.PATCHES}), looking like that stone. Like the node itself it cannot be broken, blown up,
 * pushed, or moved by a contraption, and Create's drills skip it (its hardness is -1; it is also in create:non_movable).
 */
public class NodePadBlock extends Block {
    public NodePadBlock(Properties properties) {
        super(properties);
    }
}
