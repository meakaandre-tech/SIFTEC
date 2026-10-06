package com.meakaandre.siftec.blueprint;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;

/** A saved Blueprint. Use it on a block to see where it would stand; use it there again to build it. */
public class BlueprintItem extends Item {
    public BlueprintItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (context.getLevel().getBlockState(context.getClickedPos()).getBlock() instanceof DesignerBlock) return InteractionResult.PASS;
        if (context.getPlayer() instanceof ServerPlayer player) {
            Blueprints.use(player, context.getItemInHand(), context.getClickedPos().relative(context.getClickedFace()));
        }
        return InteractionResult.SUCCESS;
    }
}
