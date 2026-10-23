package com.meakaandre.siftec.building;

import com.meakaandre.siftec.block.NodeBlock;
import com.meakaandre.siftec.node.NodePlacer;
import com.meakaandre.siftec.node.NodeSites;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/** Node buildings and their nodes: whether a spot is a node, the node a building stands over, snapping onto one. */
public final class BuildingNodes {
    /** How far either way from the aimed cell a node-bound building looks for its node, horizontally and vertically. */
    private static final int NODE_SNAP_XZ = 3, NODE_SNAP_Y = 1;

    private BuildingNodes() {
    }

    /**
     * A node building goes over a resource node, grown or mined (the node block is in its box, in a cell its template
     * leaves out), and only once the node's clump is mined away. Applies in creative too: the clump is part of the world.
     */
    static void nodeUnder(Level level, Placement p, BuildingTemplate t, Buildings.Check check) {
        BlockPos node = nodeIn(level, p, t);
        if (node == null) {
            check.fail("siftec.build_gun.no_node", p.def().name());
            return;
        }
        int left = NodePlacer.clumpLeft(level, node);
        if (left > 0) check.fail("siftec.build_gun.clump", left);
    }

    /** An ore node's position, its ore grown or the spot empty and growing back (nodes are kept by position: node.NodeSites). */
    static boolean isNode(Level level, BlockPos pos) {
        return NodeSites.isSite(level, pos);
    }

    /** The node a building stands on: an ore node for a miner, or a fluid node of its type for an extractor (oil, nitrogen). */
    static boolean isNode(BuildingDef def, Level level, BlockPos pos) {
        if (def.fluidNode().isEmpty()) return isNode(level, pos);
        return level.getBlockState(pos).getBlock() instanceof NodeBlock node && node.look.fluid() && node.look.type().id().equals(def.fluidNode());
    }

    /**
     * The node a node building stands over: the node in its own node cell (grown or mined), or null. A node anywhere
     * else in its box is not its node (and blocks it: {@link Buildings#check}).
     */
    public static @Nullable BlockPos nodeIn(Level level, Placement p, BuildingTemplate t) {
        BlockPos cell = ownNodeCell(p);
        return cell != null && isNode(p.def(), level, cell) ? cell : null;
    }

    /** Where a node building's node goes (its template's node cell), or null for any other building. */
    public static @Nullable BlockPos ownNodeCell(Placement p) {
        int[] c = p.def().nodeCell();
        return p.def().node() && c != null ? p.at(c[0], c[1], c[2]).immutable() : null;
    }

    /**
     * A node building is moved so its node cell lands on the nearest node within three blocks of where it was aimed
     * (the same height or one above or below), so the player need not line it up by hand. Anything else is left as it is.
     */
    public static Placement snapToNode(Level level, Placement p) {
        int[] c = p.def().nodeCell();
        if (!p.def().node() || c == null) return p;
        BlockPos aim = p.at(c[0], c[1], c[2]);
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(aim.offset(-NODE_SNAP_XZ, -NODE_SNAP_Y, -NODE_SNAP_XZ),
            aim.offset(NODE_SNAP_XZ, NODE_SNAP_Y, NODE_SNAP_XZ))) {
            if (!level.isLoaded(pos) || !isNode(p.def(), level, pos)) continue;
            double d = pos.distSqr(aim);
            if (d < bestDist) {
                bestDist = d;
                best = pos.immutable();
            }
        }
        return best == null ? p : p.moved(best.getX() - aim.getX(), best.getY() - aim.getY(), best.getZ() - aim.getZ());
    }
}
