package com.meakaandre.siftec.machine;

import com.meakaandre.siftec.registry.ModBlockEntities;
import com.zurrtum.create.content.kinetics.base.KineticBlock;
import com.zurrtum.create.foundation.block.IBE;
import com.zurrtum.create.infrastructure.fluids.FluidInventory;
import com.zurrtum.create.infrastructure.fluids.FluidInventoryProvider;
import com.zurrtum.create.infrastructure.items.ItemInventoryProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/**
 * The Converter and the Particle Accelerator. A shaft goes in the top. Items go in by hand, belt, funnel
 * or hopper; pipes connect on the sides. An empty-handed click takes the output; sneak and click to pick
 * which recipe it makes.
 */
public class ProcessorBlock extends KineticBlock implements IBE<ProcessorBlockEntity>, ItemInventoryProvider<ProcessorBlockEntity>,
    FluidInventoryProvider<ProcessorBlockEntity> {
    public final String machine;

    public ProcessorBlock(String machine, Properties properties) {
        super(properties);
        this.machine = machine;
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
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (stack.isEmpty()) return InteractionResult.TRY_WITH_EMPTY_HAND;
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        ProcessorBlockEntity be = getBlockEntity(level, pos);
        if (be == null || !be.insert(stack)) return InteractionResult.TRY_WITH_EMPTY_HAND;
        return InteractionResult.SUCCESS;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        withBlockEntityDo(level, pos, be -> be.use(player));
        return InteractionResult.SUCCESS;
    }

    @Override
    public @Nullable Container getInventory(LevelAccessor world, BlockPos pos, BlockState state, ProcessorBlockEntity be, @Nullable Direction context) {
        return be.items;
    }

    @Override
    public @Nullable FluidInventory getFluidInventory(LevelAccessor world, BlockPos pos, BlockState state, ProcessorBlockEntity be, @Nullable Direction side) {
        return side == Direction.UP ? null : be.tank.getCapability();
    }

    @Override
    public Class<ProcessorBlockEntity> getBlockEntityClass() {
        return ProcessorBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends ProcessorBlockEntity> getBlockEntityType() {
        return ModBlockEntities.PROCESSOR.get();
    }

    // the front looks at whoever placed it
    @Override
    protected void createBlockStateDefinition(net.minecraft.world.level.block.state.StateDefinition.Builder<net.minecraft.world.level.block.Block, net.minecraft.world.level.block.state.BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(com.meakaandre.siftec.block.Facing.FACING);
    }

    @Override
    public net.minecraft.world.level.block.state.BlockState getStateForPlacement(net.minecraft.world.item.context.BlockPlaceContext context) {
        return com.meakaandre.siftec.block.Facing.place(super.getStateForPlacement(context), context);
    }

    @Override
    protected net.minecraft.world.level.block.state.BlockState rotate(net.minecraft.world.level.block.state.BlockState state, net.minecraft.world.level.block.Rotation rotation) {
        return com.meakaandre.siftec.block.Facing.rotate(state, rotation);
    }

    @Override
    protected net.minecraft.world.level.block.state.BlockState mirror(net.minecraft.world.level.block.state.BlockState state, net.minecraft.world.level.block.Mirror mirror) {
        return com.meakaandre.siftec.block.Facing.mirror(state, mirror);
    }
}
