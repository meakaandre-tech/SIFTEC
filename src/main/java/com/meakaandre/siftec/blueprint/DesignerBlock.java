package com.meakaandre.siftec.blueprint;

import com.meakaandre.siftec.claim.Claims;
import com.meakaandre.siftec.registry.ModBlockEntities;
import com.meakaandre.siftec.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/**
 * Blueprint Designer. The framed cube above it is the build volume: anyone inside it can fly. Click the
 * designer with paper (or an old blueprint) to save what is built there as a Blueprint; sneak-click it
 * empty-handed to clear the volume.
 */
public class DesignerBlock extends Block implements EntityBlock {
    /** Blocks from the centre column to each side: 4 makes a 9-block cube, 8 a 17-block cube. */
    public final int reach;

    public DesignerBlock(int reach, Properties properties) {
        super(properties);
        this.reach = reach;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new DesignerBlockEntity(ModBlockEntities.DESIGNER.get(), pos, state);
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide() ? null : (l, p, s, be) -> {
            if (be instanceof DesignerBlockEntity designer) designer.serverTick();
        };
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        boolean paper = stack.is(Items.PAPER), old = stack.is(ModItems.BLUEPRINT.get());
        if (!paper && !old) return InteractionResult.TRY_WITH_EMPTY_HAND;
        if (!(player instanceof ServerPlayer server)) return InteractionResult.SUCCESS;
        ItemStack blueprint = Blueprints.save(level, pos, reach);
        if (blueprint.isEmpty()) {
            server.sendOverlayMessage(Component.translatable("siftec.blueprint.empty"));
            return InteractionResult.SUCCESS;
        }
        stack.shrink(1);
        server.getInventory().placeItemBackInInventory(blueprint, Prediction.SERVER_ONLY);
        server.sendOverlayMessage(Component.translatable("siftec.blueprint.saved", Blueprints.count(blueprint)));
        return InteractionResult.SUCCESS;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!(player instanceof ServerPlayer server)) return InteractionResult.SUCCESS;
        if (!player.isShiftKeyDown()) {
            server.sendOverlayMessage(Component.translatable("siftec.blueprint.how", 2 * reach + 1));
            return InteractionResult.SUCCESS;
        }
        if (!Claims.allowed(player, level, pos)) return InteractionResult.SUCCESS;
        AABB box = DesignerBlockEntity.volume(pos, reach);
        int cleared = 0;
        for (BlockPos at : BlockPos.betweenClosed((int) box.minX, (int) box.minY, (int) box.minZ, (int) box.maxX - 1, (int) box.maxY - 1, (int) box.maxZ - 1)) {
            if (level.getBlockState(at).isAir() || level.getBlockState(at).getDestroySpeed(level, at) < 0) continue;
            level.destroyBlock(at, true, player, 512);
            cleared++;
        }
        server.sendOverlayMessage(Component.translatable("siftec.blueprint.cleared", cleared));
        return InteractionResult.SUCCESS;
    }
}
