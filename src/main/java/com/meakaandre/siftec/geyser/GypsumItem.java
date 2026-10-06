package com.meakaandre.siftec.geyser;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BoneMealItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/** Gypsum, from neutralised sulfuric acid. It fertilises like bone meal. */
public class GypsumItem extends Item {
    public GypsumItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        if (BoneMealItem.growCrop(context.getItemInHand(), level, pos)) {
            if (!level.isClientSide()) level.levelEvent(1505, pos, 15);
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }
}
