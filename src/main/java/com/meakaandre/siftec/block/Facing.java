package com.meakaandre.siftec.block;

import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import org.jspecify.annotations.Nullable;

/** Which way a machine's front looks. It is placed facing whoever put it down. */
public final class Facing {
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;

    private Facing() {
    }

    public static @Nullable BlockState place(@Nullable BlockState state, BlockPlaceContext context) {
        return state == null ? null : state.setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    public static BlockState rotate(BlockState state, Rotation rotation) {
        return state.hasProperty(FACING) ? state.setValue(FACING, rotation.rotate(state.getValue(FACING))) : state;
    }

    public static BlockState mirror(BlockState state, Mirror mirror) {
        return state.hasProperty(FACING) ? state.rotate(mirror.getRotation(state.getValue(FACING))) : state;
    }
}
