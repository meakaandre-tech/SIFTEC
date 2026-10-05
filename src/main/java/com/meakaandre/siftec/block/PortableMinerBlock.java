package com.meakaandre.siftec.block;

import com.meakaandre.siftec.registry.ModBlockEntities;
import com.zurrtum.create.foundation.block.IBE;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** The first miner: needs no power, mines slowly, and has to be emptied by hand. */
public class PortableMinerBlock extends Block implements IBE<PortableMinerBlockEntity> {
    private static final VoxelShape SHAPE = Block.box(3, 0, 3, 13, 14, 13);

    public PortableMinerBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected InteractionResult useWithoutItem(
        BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit
    ) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        withBlockEntityDo(level, pos, miner -> miner.giveTo(player));
        return InteractionResult.SUCCESS;
    }

    @Override
    public Class<PortableMinerBlockEntity> getBlockEntityClass() {
        return PortableMinerBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends PortableMinerBlockEntity> getBlockEntityType() {
        return ModBlockEntities.PORTABLE_MINER.get();
    }
}
