package com.meakaandre.siftec.block;

import com.meakaandre.siftec.registry.ModBlockEntities;
import com.zurrtum.create.content.kinetics.base.KineticBlock;
import com.zurrtum.create.foundation.block.IBE;
import com.zurrtum.create.infrastructure.fluids.FluidInventory;
import com.zurrtum.create.infrastructure.fluids.FluidInventoryProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/** Resource Well Extractor: sits on the middle vent of a nitrogen node, takes a shaft on top, gives nitrogen to pipes on its sides. */
public class ExtractorBlock extends KineticBlock implements IBE<ExtractorBlockEntity>, FluidInventoryProvider<ExtractorBlockEntity> {
    public ExtractorBlock(Properties properties) {
        super(properties);
    }

    @Override
    public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state, Direction face) {
        return face == Direction.UP;
    }

    @Override
    public Axis getRotationAxis(BlockState state) {
        return Axis.Y;
    }

    @Override
    public @Nullable FluidInventory getFluidInventory(LevelAccessor world, BlockPos pos, BlockState state, ExtractorBlockEntity be, @Nullable Direction side) {
        return side == Direction.UP || side == Direction.DOWN ? null : be.tank.getCapability();
    }

    @Override
    public Class<ExtractorBlockEntity> getBlockEntityClass() {
        return ExtractorBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends ExtractorBlockEntity> getBlockEntityType() {
        return ModBlockEntities.EXTRACTOR.get();
    }
}
