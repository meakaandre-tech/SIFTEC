package com.meakaandre.siftec.engine;

import com.meakaandre.siftec.registry.ModBlockEntities;
import com.zurrtum.create.content.kinetics.base.KineticBlock;
import com.zurrtum.create.foundation.block.IBE;
import com.zurrtum.create.foundation.utility.FuelUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Furnace Engine: stands against a burning furnace and turns the shaft on top of it.
 * HUB Engine: the weaker kind. It stands against a HUB and burns fuel of its own; a HUB runs one, then two.
 */
public class EngineBlock extends KineticBlock implements IBE<EngineBlockEntity> {
    public final boolean hub;

    public EngineBlock(boolean hub, Properties properties) {
        super(properties);
        this.hub = hub;
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
        if (!hub || !FuelUtil.isFuel(stack)) return InteractionResult.TRY_WITH_EMPTY_HAND;
        if (player instanceof ServerPlayer server) {
            withBlockEntityDo(level, pos, engine -> {
                engine.addFuel(stack);
                server.sendOverlayMessage(Component.translatable(engine.statusKey(), engine.fuel.getItem(0).getCount()));
            });
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer server) {
            withBlockEntityDo(level, pos, engine -> server.sendOverlayMessage(Component.translatable(engine.statusKey(), engine.fuel.getItem(0).getCount())));
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public Class<EngineBlockEntity> getBlockEntityClass() {
        return EngineBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends EngineBlockEntity> getBlockEntityType() {
        return ModBlockEntities.ENGINE.get();
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
