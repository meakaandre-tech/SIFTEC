package com.meakaandre.siftec.death;

import com.meakaandre.siftec.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/**
 * Satisfactory's death crate: left where a player died, holding everything they carried. Only its owner opens it,
 * by clicking it, and everything goes straight back where it was ({@link DeathCrates#open}). It cannot be broken,
 * blown up or pushed, and never despawns; the map shows it as its owner's death marker until it is emptied.
 */
public class DeathCrateBlock extends Block implements EntityBlock {
    public DeathCrateBlock(Properties properties) {
        super(properties);
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new DeathCrateBlockEntity(ModBlockEntities.DEATH_CRATE.get(), pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer server) DeathCrates.open(server, pos);
        return InteractionResult.SUCCESS;
    }
}
