package com.meakaandre.siftec.geyser;

import com.meakaandre.siftec.registry.ModBlockEntities;
import com.zurrtum.create.content.kinetics.base.KineticBlock;
import com.zurrtum.create.foundation.block.IBE;
import com.zurrtum.create.infrastructure.fluids.FluidInventory;
import com.zurrtum.create.infrastructure.fluids.FluidInventoryProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/**
 * Geyser Engine: built over a sulfur geyser. Every eruption drives it hard for a few seconds and leaves
 * sulfuric acid in its tank, which has to be piped away or the engine stops.
 */
public class GeyserEngineBlock extends KineticBlock implements IBE<GeyserEngineBlockEntity>, FluidInventoryProvider<GeyserEngineBlockEntity> {
    public GeyserEngineBlock(Properties properties) {
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
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer server) {
            withBlockEntityDo(level, pos, engine -> server.sendOverlayMessage(Component.translatable(engine.statusKey(), engine.acidMb())));
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public @Nullable FluidInventory getFluidInventory(LevelAccessor world, BlockPos pos, BlockState state, GeyserEngineBlockEntity be, @Nullable Direction side) {
        return side == Direction.UP || side == Direction.DOWN ? null : be.tank.getCapability();
    }

    @Override
    public Class<GeyserEngineBlockEntity> getBlockEntityClass() {
        return GeyserEngineBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends GeyserEngineBlockEntity> getBlockEntityType() {
        return ModBlockEntities.GEYSER_ENGINE.get();
    }

    // the front looks at whoever placed it
    @Override
    protected void createBlockStateDefinition(net.minecraft.world.level.block.state.StateDefinition.Builder<net.minecraft.world.level.block.Block, net.minecraft.world.level.block.state.BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(com.meakaandre.siftec.block.Facing.FACING);
    }

    @Override
    public net.minecraft.world.level.block.state.BlockState getStateForPlacement(net.minecraft.world.item.context.BlockPlaceContext context) {
        // right on the geyser's water the engine would shut the gas in, and a geyser that cannot breathe stops: leave a block of air
        if (GeyserEngineBlockEntity.blocksVent(context.getLevel(), context.getClickedPos())) {
            if (context.getPlayer() instanceof ServerPlayer player) player.sendOverlayMessage(Component.translatable("siftec.geyser.blocked_place"));
            return null;
        }
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
