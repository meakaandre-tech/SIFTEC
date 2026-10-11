package com.meakaandre.siftec.prefab;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A bunk in the HUB's living quarters. It is a real bed: sleeping in it sets the respawn point, and it is in the
 * minecraft:beds tag. Like the rest of the HUB it cannot be broken on its own, and where a bed would explode (the
 * Nether, the End) this one simply refuses, so the HUB is never left without it.
 */
public class HubBedBlock extends BedBlock implements PrefabPart {
    public HubBedBlock(Properties properties) {
        super(DyeColor.ORANGE, properties);
    }

    @Override
    protected InteractionResult destroyOnUse(BlockState state, Level level, BlockPos pos, Player player) {
        if (!level.isClientSide()) player.sendOverlayMessage(Component.translatable("siftec.prefab.no_sleep"));
        return InteractionResult.SUCCESS;
    }

    @Override
    protected void destroyOnLeave(Level level, BlockPos pos) {
    }
}
