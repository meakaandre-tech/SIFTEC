package com.meakaandre.siftec.hub;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * HUB Planner: click two opposite corners of the HUB building, then click the HUB with it to measure the
 * building. Sneak and use it in the air to start again.
 */
public class HubPlannerItem extends Item {
    private static final String A = "siftec_corner_a", B = "siftec_corner_b";

    public HubPlannerItem(Properties properties) {
        super(properties);
    }

    private static CompoundTag tag(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? new CompoundTag() : data.copyTag();
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (context.getLevel().getBlockState(context.getClickedPos()).getBlock() instanceof HubBlock) return InteractionResult.PASS;
        if (!(context.getPlayer() instanceof ServerPlayer player)) return InteractionResult.SUCCESS;
        ItemStack stack = context.getItemInHand();
        CompoundTag tag = tag(stack);
        BlockPos pos = context.getClickedPos();
        if (!tag.contains(A) || tag.contains(B)) {
            tag.remove(B);
            tag.putLong(A, pos.asLong());
            player.sendOverlayMessage(Component.translatable("siftec.planner.first"));
        } else {
            tag.putLong(B, pos.asLong());
            BlockPos a = BlockPos.of(tag.getLongOr(A, 0));
            player.sendOverlayMessage(Component.translatable("siftec.planner.second",
                Math.abs(a.getX() - pos.getX()) + 1, Math.abs(a.getY() - pos.getY()) + 1, Math.abs(a.getZ() - pos.getZ()) + 1));
        }
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (!player.isShiftKeyDown()) return InteractionResult.PASS;
        if (player instanceof ServerPlayer server) {
            player.getItemInHand(hand).remove(DataComponents.CUSTOM_DATA);
            server.sendOverlayMessage(Component.translatable("siftec.planner.cleared"));
        }
        return InteractionResult.SUCCESS;
    }

    /** The marked box as {min, max}, or null until both corners are set. */
    public static BlockPos @Nullable [] box(ItemStack stack) {
        CompoundTag tag = tag(stack);
        if (!tag.contains(A) || !tag.contains(B)) return null;
        BlockPos a = BlockPos.of(tag.getLongOr(A, 0)), b = BlockPos.of(tag.getLongOr(B, 0));
        return new BlockPos[]{
            new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ())),
            new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()))};
    }
}
