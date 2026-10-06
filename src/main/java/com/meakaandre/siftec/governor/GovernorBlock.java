package com.meakaandre.siftec.governor;

import com.meakaandre.siftec.registry.ModBlockEntities;
import com.zurrtum.create.content.kinetics.base.AbstractEncasedShaftBlock;
import com.zurrtum.create.foundation.block.IBE;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Speed Governor: a shaft goes in one end and comes out the other at the speed you pick, whatever the
 * speed going in. Click it to choose; the most it will give is the speed limit of the company it belongs to.
 */
public class GovernorBlock extends AbstractEncasedShaftBlock implements IBE<GovernorBlockEntity> {
    public GovernorBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer server) withBlockEntityDo(level, pos, governor -> GovernorMenu.open(server, governor));
        return InteractionResult.SUCCESS;
    }

    @Override
    public Class<GovernorBlockEntity> getBlockEntityClass() {
        return GovernorBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends GovernorBlockEntity> getBlockEntityType() {
        return ModBlockEntities.GOVERNOR.get();
    }
}
