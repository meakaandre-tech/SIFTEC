package com.meakaandre.siftec.geyser;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import org.jspecify.annotations.Nullable;

/**
 * Sulfur geysers (vanilla potent sulfur over magma, under water) as the Geothermal Generator finds them: it stands
 * over one with at least one block of air between the water and itself, so the geyser's gas still escapes and its own
 * clock keeps running.
 */
public final class Vents {
    /** How far down the vent may be: a gap of air, then the geyser's water (vanilla allows up to four blocks of it). */
    public static final int REACH = 8;
    /** Blocks above the vent looked at: up to four of water, then a free one. */
    private static final int GAS_RISE = 5;

    private Vents() {
    }

    /**
     * The potent sulfur block under this position, looking down through air and then the geyser's water; null
     * if there is none in reach or something solid is in the way.
     */
    public static @Nullable BlockPos findVent(Level level, BlockPos from) {
        boolean water = false;
        for (int i = 1; i <= REACH; i++) {
            BlockPos at = from.below(i);
            BlockState state = level.getBlockState(at);
            if (state.is(Blocks.POTENT_SULFUR)) return water ? at : null;
            if (level.getFluidState(at).isSourceOfType(Fluids.WATER)) {
                water = true;
                continue;
            }
            // air (or anything gas passes) above the water; once in the water, only water
            if (water || !passable(level, at, state)) return null;
        }
        return null;
    }

    private static boolean passable(Level level, BlockPos pos, BlockState state) {
        return state.isAir() || state.getCollisionShape(level, pos).isEmpty() && state.getFluidState().isEmpty();
    }

    /**
     * True if the vent's gas reaches open air: the way vanilla's geyser looks for it (up through at most four
     * water blocks, then a free block). When it does not, the geyser's own clock stands still.
     */
    public static boolean gasEscapes(Level level, BlockPos vent) {
        for (int y = 1; y <= GAS_RISE; y++) {
            BlockPos at = vent.above(y);
            BlockState state = level.getBlockState(at);
            if (level.getFluidState(at).isSourceOfType(Fluids.WATER) && (state.is(Blocks.WATER) || passable(level, at, state))) continue;
            return passable(level, at, state);
        }
        return false;
    }
}
