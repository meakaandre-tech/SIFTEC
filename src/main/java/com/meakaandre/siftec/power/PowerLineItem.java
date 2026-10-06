package com.meakaandre.siftec.power;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Power Line: click one pole, then another in range, to join them. Each join uses one line. */
public class PowerLineItem extends Item {
    private static final Map<UUID, BlockPos> FIRST = new ConcurrentHashMap<>();

    public PowerLineItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        if (!(level.getBlockEntity(pos) instanceof PoleBlockEntity pole)) return InteractionResult.PASS;
        if (!(context.getPlayer() instanceof ServerPlayer player)) return InteractionResult.SUCCESS;
        BlockPos first = FIRST.remove(player.getUUID());
        if (first == null || first.equals(pos) || !(level.getBlockEntity(first) instanceof PoleBlockEntity other)) {
            FIRST.put(player.getUUID(), pos);
            player.sendOverlayMessage(Component.translatable("siftec.line.first"));
            return InteractionResult.SUCCESS;
        }
        int range = Math.min(range(level, pos), range(level, first));
        if (Math.sqrt(first.distSqr(pos)) > range) {
            player.sendOverlayMessage(Component.translatable("siftec.line.too_far", range));
            return InteractionResult.SUCCESS;
        }
        if (pole.lines.size() >= PoleBlockEntity.MAX_LINES || other.lines.size() >= PoleBlockEntity.MAX_LINES) {
            player.sendOverlayMessage(Component.translatable("siftec.line.full", PoleBlockEntity.MAX_LINES));
            return InteractionResult.SUCCESS;
        }
        if (pole.link(first) | other.link(pos)) {
            if (!player.hasInfiniteMaterials()) context.getItemInHand().shrink(1);
            player.sendOverlayMessage(Component.translatable("siftec.line.joined"));
        }
        return InteractionResult.SUCCESS;
    }

    private static int range(Level level, BlockPos pos) {
        return level.getBlockState(pos).getBlock() instanceof PoleBlock pole ? pole.range : 0;
    }
}
