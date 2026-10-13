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
 * The motor inside a powered building: it turns the building's Create machines through the face it points at, with
 * exactly the SU the building draws. It stands in for Create: New Age's electric motor until the buildings are wired
 * to the grid (work package 6); until then it runs whenever the building stands.
 */
public class MotorBlock extends DirectionalKineticBlock implements IBE<MotorBlockEntity> {
    public MotorBlock(Properties properties) {
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
    public Class<MotorBlockEntity> getBlockEntityClass() {
        return MotorBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends MotorBlockEntity> getBlockEntityType() {
        return ModBlockEntities.BUILDING_MOTOR.get();
    }
}
