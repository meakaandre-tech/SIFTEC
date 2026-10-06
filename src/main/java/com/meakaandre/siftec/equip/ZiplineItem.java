package com.meakaandre.siftec.equip;

import com.meakaandre.siftec.power.PoleBlock;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;

/** Zipline: use it on a Power Pole or Tower to ride the Power Line you are facing. */
public class ZiplineItem extends Item {
    public ZiplineItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (!(context.getLevel().getBlockState(context.getClickedPos()).getBlock() instanceof PoleBlock)) return InteractionResult.PASS;
        if (context.getPlayer() instanceof ServerPlayer player && !Ziplines.start(player, context.getClickedPos())) {
            player.sendOverlayMessage(Component.translatable("siftec.zipline.no_line"));
        }
        return InteractionResult.SUCCESS;
    }
}
