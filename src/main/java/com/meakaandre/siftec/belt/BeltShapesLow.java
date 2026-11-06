package com.meakaandre.siftec.belt;

import com.zurrtum.create.content.kinetics.belt.BeltBlock;
import com.zurrtum.create.content.kinetics.belt.BeltSlope;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.EnumMap;
import java.util.Map;

/**
 * The one-block belt's shapes: a flat belt is its 5 px rails on the floor of its block (players walk on and across it
 * and are carried along), a vertical one a 5 px strip against one face of its block. Sloped and encased belts keep
 * Create's shapes.
 */
public final class BeltShapesLow {
    /** The rails' height in pixels; the band itself is 4 px. */
    public static final int RAIL_PX = 5, BAND_PX = 4;
    private static final VoxelShape FLAT = Block.box(0, 0, 0, 16, RAIL_PX, 16);
    private static final Map<Direction, VoxelShape> VERTICAL = new EnumMap<>(Direction.class);

    static {
        VERTICAL.put(Direction.NORTH, Block.box(0, 0, 0, 16, 16, RAIL_PX));
        VERTICAL.put(Direction.SOUTH, Block.box(0, 0, 16 - RAIL_PX, 16, 16, 16));
        VERTICAL.put(Direction.WEST, Block.box(0, 0, 0, RAIL_PX, 16, 16));
        VERTICAL.put(Direction.EAST, Block.box(16 - RAIL_PX, 0, 0, 16, 16, 16));
    }

    private BeltShapesLow() {
    }

    /** The belt's own shape (no attachment), or {@code create}'s for slopes, casings and sideways belts. */
    public static VoxelShape shape(BlockState state, VoxelShape create) {
        if (!state.hasProperty(BeltBlock.SLOPE) || state.getValue(BeltBlock.CASING)) return create;
        BeltSlope slope = state.getValue(BeltBlock.SLOPE);
        if (slope == BeltSlope.HORIZONTAL) return FLAT;
        if (slope == BeltSlope.VERTICAL) return VERTICAL.get(backFace(state));
        return create;
    }

    /** The face a vertical belt lies against: the one its facing points to. */
    public static Direction backFace(BlockState state) {
        return state.getValue(BeltBlock.HORIZONTAL_FACING);
    }
}
