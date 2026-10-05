package com.meakaandre.siftec.block;

import com.meakaandre.siftec.registry.ModBlockEntities;
import com.zurrtum.create.content.kinetics.base.KineticBlock;
import com.zurrtum.create.foundation.block.IBE;
import com.zurrtum.create.infrastructure.items.ItemInventoryProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/** A powered miner. Sits on the middle block of a node and takes rotation from a shaft on top. */
public class MinerBlock extends KineticBlock implements IBE<MinerBlockEntity>, ItemInventoryProvider<MinerBlockEntity> {
    public final MinerTier tier;

    public MinerBlock(MinerTier tier, Properties properties) {
        super(properties);
        this.tier = tier;
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
    protected InteractionResult useWithoutItem(
        BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit
    ) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        withBlockEntityDo(level, pos, miner -> miner.giveTo(player));
        return InteractionResult.SUCCESS;
    }

    @Override
    public @Nullable Container getInventory(
        LevelAccessor world, BlockPos pos, BlockState state, MinerBlockEntity blockEntity, @Nullable Direction context
    ) {
        return blockEntity.output;
    }

    @Override
    public Class<MinerBlockEntity> getBlockEntityClass() {
        return MinerBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends MinerBlockEntity> getBlockEntityType() {
        return ModBlockEntities.MINER.get();
    }
}
