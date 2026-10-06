package com.meakaandre.siftec.blueprint;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;

/** A saved Blueprint. Use it on a block to see where it would stand; use it there again to build it. Sneak and use it in the air to turn it. */
public class BlueprintItem extends Item {
    public BlueprintItem(Properties properties) {
        super(properties);
    }

    /** Sneak and use it in the air to turn the blueprint a quarter turn. */
    @Override
    public InteractionResult use(net.minecraft.world.level.Level level, net.minecraft.world.entity.player.Player player, net.minecraft.world.InteractionHand hand) {
        if (!player.isShiftKeyDown()) return InteractionResult.PASS;
        if (player instanceof ServerPlayer server) {
            int turns = Blueprints.rotate(server, player.getItemInHand(hand));
            server.sendOverlayMessage(net.minecraft.network.chat.Component.translatable("siftec.blueprint.turned", turns * 90));
        }
        return InteractionResult.SUCCESS;
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
