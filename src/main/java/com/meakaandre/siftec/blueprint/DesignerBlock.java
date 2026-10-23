package com.meakaandre.siftec.blueprint;

import com.meakaandre.siftec.building.Buildings;
import com.meakaandre.siftec.building.ControllerBlock;
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
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/**
 * The Blueprint Designer's terminal, and the controller of the Blueprint Designer building (Mk.1 to Mk.3, put down and
 * taken away only with the Build Gun). It is set into the middle of the building's front side at floor level, facing
 * out; the cube over the building's floor behind it is the build volume, and anyone inside it can fly. Click the
 * terminal with paper (or an old blueprint) to save what is built there as a Blueprint; sneak-click it empty-handed to
 * clear the volume. Anything else it does is a building controller's (the Customizer, its status).
 */
public class DesignerBlock extends ControllerBlock {
    /** Blocks from the volume's middle column to each side: 4 makes a 9-block cube (Mk.1), 6 a 13-block cube (Mk.2), 8 a 17-block cube (Mk.3). */
    public final int reach;

    public DesignerBlock(int reach, Properties properties) {
        super(properties);
        this.reach = reach;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new DesignerBlockEntity(ModBlockEntities.DESIGNER.get(), pos, state);
    }

    /** The terminal's building, or null (and the player told) if it stands on its own, outside any Blueprint Designer. */
    private static @Nullable DesignerBlockEntity designer(Level level, BlockPos pos, ServerPlayer player) {
        if (level.getBlockEntity(pos) instanceof DesignerBlockEntity designer && designer.placement() != null) return designer;
        player.sendOverlayMessage(Component.translatable("siftec.blueprint.loose"));
        return null;
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        boolean paper = stack.is(Items.PAPER), old = stack.is(ModItems.BLUEPRINT.get());
        if (!paper && !old) return super.useItemOn(stack, state, level, pos, player, hand, hit);
        if (!(player instanceof ServerPlayer server)) return InteractionResult.SUCCESS;
        DesignerBlockEntity designer = designer(level, pos, server);
        if (designer == null) return InteractionResult.SUCCESS;
        ItemStack blueprint = Blueprints.save(level, designer.volume());
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
        DesignerBlockEntity designer = designer(level, pos, server);
        if (designer == null) return InteractionResult.SUCCESS;
        if (!player.isShiftKeyDown()) {
            server.sendOverlayMessage(Component.translatable("siftec.blueprint.how", 2 * reach + 1));
            return InteractionResult.SUCCESS;
        }
        if (!Claims.allowed(player, level, pos)) return InteractionResult.SUCCESS;
        AABB box = designer.volume();
        int cleared = 0;
        for (BlockPos at : BlockPos.betweenClosed((int) box.minX, (int) box.minY, (int) box.minZ, (int) box.maxX - 1, (int) box.maxY - 1, (int) box.maxZ - 1)) {
            // a Build Gun building's blocks come down only with the Build Gun, never with the Designer
            if (level.getBlockState(at).isAir() || level.getBlockState(at).getDestroySpeed(level, at) < 0 || !Claims.allowed(player, level, at)
                || Buildings.protectedAt(level, at)) continue;
            level.destroyBlock(at, true, player, 512);
            cleared++;
        }
        server.sendOverlayMessage(Component.translatable("siftec.blueprint.cleared", cleared));
        return InteractionResult.SUCCESS;
    }
}
