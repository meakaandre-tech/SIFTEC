package com.meakaandre.siftec.geyser;

import com.meakaandre.siftec.node.NodeMap;
import com.meakaandre.siftec.node.Purity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * A sulfur geyser's purity, as Satisfactory's geysers have one: it sets what a Geothermal Generator on it gives (impure
 * 100 MW, normal 200, pure 400 on average). It belongs to the geyser's place in its world, the way a node's does: impure
 * is the common one near spawn and pure far out, and the same geyser always has the same purity.
 */
public final class GeyserPurity {
    private GeyserPurity() {
    }

    public static Purity of(ServerLevel level, BlockPos vent) {
        long h = NodeMap.mix(level.getSeed() ^ vent.asLong() * 0x9E3779B97F4A7C15L ^ 0x6765797365724CL);
        int[] o = NodeMap.origin();
        double dx = vent.getX() - (o == null ? 0 : o[0]), dz = vent.getZ() - (o == null ? 0 : o[1]);
        return NodeMap.purityOf(h, Math.sqrt(dx * dx + dz * dz));
    }
}
