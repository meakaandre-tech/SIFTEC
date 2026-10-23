package com.meakaandre.siftec.block;

import com.meakaandre.siftec.registry.ModBlockEntities;
import com.zurrtum.create.content.kinetics.base.KineticBlock;
import com.zurrtum.create.foundation.block.IBE;
import com.zurrtum.create.infrastructure.fluids.FluidInventory;
import com.zurrtum.create.infrastructure.fluids.FluidInventoryProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
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

    /** Says why it gives nothing, if it does not (no running Pressurizer near, or every one near is full). */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof ExtractorBlockEntity be) || be.problem.isEmpty()) return InteractionResult.PASS;
        if (player instanceof ServerPlayer server) {
            server.sendOverlayMessage(Component.translatable("siftec.building.status", state.getBlock().getName(),
                Component.translatable(be.problem)));
        }
        return InteractionResult.SUCCESS;
    }

    // the front looks at whoever placed it
    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(Facing.FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
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
