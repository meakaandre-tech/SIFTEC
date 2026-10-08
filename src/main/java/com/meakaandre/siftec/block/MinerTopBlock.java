package com.meakaandre.siftec.block;

import com.meakaandre.siftec.registry.ModBlockEntities;
import com.zurrtum.create.content.kinetics.base.KineticBlock;
import com.zurrtum.create.content.kinetics.base.KineticBlockEntity;
import com.zurrtum.create.foundation.block.IBE;
import com.zurrtum.create.infrastructure.fluids.FluidInventory;
import com.zurrtum.create.infrastructure.fluids.FluidInventoryProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/**
 * The upper block of a miner: the top of its tall casing. It carries rotation from a shaft above down into the
 * miner like a shaft would. The miner's model and renderer draw the whole machine, so this block shows nothing of
 * its own. Breaking it breaks the miner (which drops the miner); it goes when the miner goes.
 */
public class MinerTopBlock extends KineticBlock implements IBE<KineticBlockEntity>, FluidInventoryProvider<KineticBlockEntity> {
    public MinerTopBlock(Properties properties) {
        super(properties);
    }

    @Override
    public Axis getRotationAxis(BlockState state) {
        return Axis.Y;
    }

    @Override
    public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state, Direction face) {
        return face.getAxis() == Axis.Y;
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return level.getBlockState(pos.below()).getBlock() instanceof MinerBlock;
    }

    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos, Direction direction,
                                     BlockPos neighbourPos, BlockState neighbour, RandomSource random) {
        if (direction == Direction.DOWN && !(neighbour.getBlock() instanceof MinerBlock)) return Blocks.AIR.defaultBlockState();
        return super.updateShape(state, level, ticks, pos, direction, neighbourPos, neighbour, random);
    }

    /** Breaking the top breaks the miner below it, which drops the miner. */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        BlockPos below = pos.below();
        if (!level.isClientSide() && level.getBlockState(below).getBlock() instanceof MinerBlock) {
            level.destroyBlock(below, !player.hasInfiniteMaterials(), player);
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    /** Clicking the top is clicking the miner. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        BlockState below = level.getBlockState(pos.below());
        if (below.getBlock() instanceof MinerBlock miner) return miner.useWithoutItem(below, level, pos.below(), player, hit.withPosition(pos.below()));
        return InteractionResult.PASS;
    }

    @Override
    protected ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state, boolean includeData) {
        BlockState below = level.getBlockState(pos.below());
        return below.getBlock() instanceof MinerBlock ? new ItemStack(below.getBlock()) : ItemStack.EMPTY;
    }

    @Override
    public @Nullable FluidInventory getFluidInventory(LevelAccessor world, BlockPos pos, BlockState state, KineticBlockEntity be, @Nullable Direction side) {
        if (side == Direction.UP) return null;
        return world.getBlockEntity(pos.below()) instanceof MinerBlockEntity miner ? miner.tank.getCapability() : null;
    }

    @Override
    public Class<KineticBlockEntity> getBlockEntityClass() {
        return KineticBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends KineticBlockEntity> getBlockEntityType() {
        return ModBlockEntities.MINER_TOP.get();
    }
}
