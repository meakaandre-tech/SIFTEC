package com.meakaandre.siftec.building;

import com.meakaandre.siftec.registry.ModBlockEntities;
import com.zurrtum.create.content.kinetics.base.DirectionalKineticBlock;
import com.zurrtum.create.foundation.block.IBE;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The turbine inside a generator building: it turns the generator coils through the face it points at while the
 * generator runs ({@link TurbineBlockEntity}).
 */
public class TurbineBlock extends DirectionalKineticBlock implements IBE<TurbineBlockEntity> {
    public TurbineBlock(Properties properties) {
        super(properties);
    }

    @Override
    public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state, Direction face) {
        return face == state.getValue(FACING);
    }

    @Override
    public Axis getRotationAxis(BlockState state) {
        return state.getValue(FACING).getAxis();
    }

    @Override
    public Class<TurbineBlockEntity> getBlockEntityClass() {
        return TurbineBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends TurbineBlockEntity> getBlockEntityType() {
        return ModBlockEntities.TURBINE.get();
    }
}
