package com.meakaandre.siftec.compat;

import com.meakaandre.siftec.node.Node;
import com.meakaandre.siftec.node.NodePlacer;
import com.meakaandre.siftec.node.NodeType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/**
 * Oil for Create: Diesel Generators. The mod's own oil map is switched off: a chunk has oil only where one
 * of the pack's oil nodes is, the node never runs dry, and its purity sets how much each stroke brings up.
 */
public final class OilNodes {
    private OilNodes() {
    }

    /** What the Pumpjack is told the chunk holds, in mB. It takes at most this much per stroke. */
    public static int amount(ServerLevel level, ChunkPos chunk) {
        for (Node node : NodePlacer.nodesTouching(level, chunk.x(), chunk.z())) {
            if (node.type() != NodeType.OIL) continue;
            return switch (node.purity()) {
                case IMPURE -> 125;
                case NORMAL -> 250;
                case PURE -> 500;
            };
        }
        return 0;
    }
}
