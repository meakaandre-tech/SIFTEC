package com.meakaandre.siftec.power;

import com.meakaandre.siftec.registry.ModBlockEntities;
import com.zurrtum.create.content.kinetics.base.KineticBlock;
import com.zurrtum.create.foundation.block.IBE;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Power Pole and Power Tower: a shaft comes in from below, and Power Lines carry the rotation to other poles. */
public class PoleBlock extends KineticBlock implements IBE<PoleBlockEntity> {
    private static final VoxelShape SHAPE = Block.box(5, 0, 5, 11, 16, 11);
    /** How far a Power Line from this pole can reach, in blocks. */
    public final int range;

    public PoleBlock(int range, Properties properties) {
        super(properties);
        this.range = range;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state, Direction face) {
        return face == Direction.DOWN;
    }

    @Override
    public Axis getRotationAxis(BlockState state) {
        return Axis.Y;
    }

    @Override
    public Class<PoleBlockEntity> getBlockEntityClass() {
        return PoleBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends PoleBlockEntity> getBlockEntityType() {
        return ModBlockEntities.POLE.get();
    }
}
