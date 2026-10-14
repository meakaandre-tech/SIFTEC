package com.meakaandre.siftec.sink;

import com.meakaandre.siftec.block.Facing;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * A decoration from the AWESOME Shop: a statue, a light, a piece of office furniture. It faces whoever put it down and
 * does nothing else; its shape is one box round the model, the same whichever way it faces.
 */
public class DecorBlock extends Block {
    private final VoxelShape shape;

    public DecorBlock(VoxelShape shape, Properties properties) {
        super(properties);
        this.shape = shape;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shape;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(Facing.FACING);
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        return Facing.place(super.getStateForPlacement(context), context);
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return Facing.rotate(state, rotation);
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return Facing.mirror(state, mirror);
    }
}
