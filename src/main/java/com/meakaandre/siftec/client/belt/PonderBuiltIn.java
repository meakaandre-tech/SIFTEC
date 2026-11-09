package com.meakaandre.siftec.client.belt;

import com.meakaandre.siftec.belt.BeltAttachments;
import com.zurrtum.create.AllBlocks;
import com.zurrtum.create.client.ponder.api.level.PonderLevel;
import com.zurrtum.create.content.kinetics.belt.BeltBlock;
import com.zurrtum.create.content.kinetics.belt.BeltSlope;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Create's Ponder scenes for funnels, tunnels and belts keep their funnels and tunnels where Create puts them, in the
 * block above a belt, so that every one of Create's scene steps (flaps, filters, modes, items) works as written. Ponder
 * draws each of them as SIFTEC builds them in: in the belt's own block (one block lower), and the belt under it without
 * the casing Create puts under a tunnel, as a belt with a built-in tunnel has none.
 */
public final class PonderBuiltIn {
    private PonderBuiltIn() {
    }

    /** True if this is a Ponder scene's funnel or tunnel standing on a belt, drawn built into the belt's block below. */
    public static boolean lowered(Level level, BlockPos pos) {
        return level instanceof PonderLevel && BeltAttachments.fits(level.getBlockState(pos)) && carries(level.getBlockState(pos.below()));
    }

    /** The belt's state as a Ponder scene draws it: without its casing when a funnel or tunnel stands on it. */
    public static BlockState drawn(Level level, BlockPos pos, BlockState state) {
        if (!(level instanceof PonderLevel) || !state.is(AllBlocks.BELT) || !state.getValue(BeltBlock.CASING)) return state;
        return BeltAttachments.fits(level.getBlockState(pos.above())) && carries(state) ? state.setValue(BeltBlock.CASING, false) : state;
    }

    /**
     * Where a Ponder text or input window points: a point in a funnel's or tunnel's cell drawn built in (above its lower
     * face, up to and on its top face) moves down a block with it.
     */
    public static Vec3 anchor(Level level, Vec3 point) {
        if (!(level instanceof PonderLevel)) return point;
        BlockPos cell = BlockPos.containing(point.x, point.y - 1e-4, point.z);
        return lowered(level, cell) ? point.subtract(0, 1, 0) : point;
    }

    /** A belt block that takes a funnel or tunnel built in, its casing aside: flat or sloped. */
    private static boolean carries(BlockState belt) {
        if (!belt.is(AllBlocks.BELT)) return false;
        BeltSlope slope = belt.getValue(BeltBlock.SLOPE);
        return slope == BeltSlope.HORIZONTAL || slope == BeltSlope.UPWARD || slope == BeltSlope.DOWNWARD;
    }
}
