package com.meakaandre.siftec.mam;

import com.meakaandre.siftec.company.Companies;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** The MAM: the research block. Any MAM shows the research of the company of whoever opens it. */
public class MamBlock extends Block {
    public MamBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer server) MamMenu.open(server, Companies.of(server));
        return InteractionResult.SUCCESS;
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
