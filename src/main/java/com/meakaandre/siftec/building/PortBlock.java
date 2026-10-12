package com.meakaandre.siftec.building;

import com.meakaandre.siftec.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import org.jspecify.annotations.Nullable;

/**
 * A building's input or output, as in Satisfactory: belts, funnels and pipes attach only here, on the face that
 * points out of the building ({@code facing}). An input passes what it is given to its target inside (a basin, a
 * depot, a tank); an output lets things be taken from its target.
 */
public class PortBlock extends Block implements EntityBlock {
    public enum Kind implements StringRepresentable {
        ITEM_IN, ITEM_OUT, FLUID_IN, FLUID_OUT;

        @Override
        public String getSerializedName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        public boolean input() {
            return this == ITEM_IN || this == FLUID_IN;
        }

        public boolean fluid() {
            return this == FLUID_IN || this == FLUID_OUT;
        }
    }

    public static final EnumProperty<Direction> FACING = BlockStateProperties.FACING;
    public static final EnumProperty<Kind> KIND = EnumProperty.create("kind", Kind.class);

    public PortBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.SOUTH).setValue(KIND, Kind.ITEM_IN));
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PortBlockEntity(ModBlockEntities.BUILDING_PORT.get(), pos, state);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(FACING, KIND);
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }
}
