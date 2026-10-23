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
    /** Oil per Pumpjack stroke from an impure, normal and pure node (mB). */
    private static final int IMPURE_MB = 125, NORMAL_MB = 250, PURE_MB = 500;

    private OilNodes() {
    }

    /** What the Pumpjack is told the chunk holds, in mB. It takes at most this much per stroke. */
    public static int amount(ServerLevel level, ChunkPos chunk) {
        for (Node node : NodePlacer.nodesTouching(level, chunk.x(), chunk.z())) {
            if (node.type() != NodeType.OIL) continue;
            return switch (node.purity()) {
                case IMPURE -> IMPURE_MB;
                case NORMAL -> NORMAL_MB;
                case PURE -> PURE_MB;
            };
        }
        return 0;
    }
}
