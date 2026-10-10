package com.meakaandre.siftec.workshop;

import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/**
 * Builds the pack's own blocks and equipment from a list of parts. No grid. By hand from the window, or
 * automatically: pick a build with a right-click and feed it the parts by belt, funnel, chute or hopper.
 */
public class WorkshopBlock extends Block implements EntityBlock {
    public WorkshopBlock(Properties properties) {
        super(properties);
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new WorkshopBlockEntity(ModBlockEntities.WORKSHOP.get(), pos, state);
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide() ? null : (l, p, s, be) -> {
            if (be instanceof WorkshopBlockEntity workshop) workshop.serverTick();
        };
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer server) {
            // a Workshop placed before it had a block entity gets one now
            BlockEntity found = level.getChunkAt(pos).getBlockEntity(pos, net.minecraft.world.level.chunk.LevelChunk.EntityCreationType.IMMEDIATE);
            WorkshopMenu.open(server, Companies.of(server), found instanceof WorkshopBlockEntity be ? be : null);
        }
        return InteractionResult.SUCCESS;
    }
}
