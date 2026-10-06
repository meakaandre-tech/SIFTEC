package com.meakaandre.siftec.workshop;

import com.meakaandre.siftec.company.Companies;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** Builds the pack's own blocks and equipment from a list of parts. No grid. */
public class WorkshopBlock extends Block {
    public WorkshopBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer server) WorkshopMenu.open(server, Companies.of(server));
        return InteractionResult.SUCCESS;
    }
}
