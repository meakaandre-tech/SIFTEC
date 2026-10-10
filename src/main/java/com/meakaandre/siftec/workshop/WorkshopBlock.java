package com.meakaandre.siftec.workshop;

import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
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
 * Builds the pack's own blocks and equipment from a list of parts, by hand. No grid. Machines make the same
 * builds without it (sequenced assembly and the Mechanical Crafter; see design/recipes.txt).
 */
public class WorkshopBlock extends Block implements EntityBlock {
    public WorkshopBlock(Properties properties) {
        super(properties);
    }

    /** Only there to give back what the old automatic mode held (see {@link WorkshopBlockEntity}). */
    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new WorkshopBlockEntity(ModBlockEntities.WORKSHOP.get(), pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer server) {
            if (level.getBlockEntity(pos) instanceof WorkshopBlockEntity workshop && workshop.giveBack(server)) {
                server.sendSystemMessage(Component.translatable("siftec.workshop.leftover"));
            }
            WorkshopMenu.open(server, Companies.of(server));
        }
        return InteractionResult.SUCCESS;
    }
}
