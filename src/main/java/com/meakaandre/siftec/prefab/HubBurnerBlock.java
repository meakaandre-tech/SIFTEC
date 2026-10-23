package com.meakaandre.siftec.prefab;

import com.meakaandre.siftec.block.Facing;
import com.meakaandre.siftec.power.PowerGrids;
import com.meakaandre.siftec.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/**
 * The fuel inlet of one of the HUB's two built-in Biomass Burners (Satisfactory's HUB Biomass Burners): a 30 MW
 * generator, 6,144 SU, built as the Biomass Burner building is (a Blaze Burner under a boiler, a Steam Engine and a
 * generator coil behind it), on the grid of the wire connector on top of it ({@link HubBurnerBlockEntity}). HUB Upgrade 2 adds the first, HUB
 * Upgrade 5 the second. Used with fuel it takes the fuel; used empty-handed it says how it is doing; sneaking, it
 * resets its grid's blown fuse.
 */
public class HubBurnerBlock extends Block implements EntityBlock, PrefabPart {
    public HubBurnerBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(Facing.FACING, Direction.NORTH));
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new HubBurnerBlockEntity(ModBlockEntities.HUB_BURNER.get(), pos, state);
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                                                                 BlockEntityType<T> type) {
        return level.isClientSide() ? null : (l, p, s, be) -> {
            if (be instanceof HubBurnerBlockEntity burner) burner.serverTick();
        };
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof HubBurnerBlockEntity burner)) return InteractionResult.TRY_WITH_EMPTY_HAND;
        if (!HubBurnerBlockEntity.accepts(stack)) {
            if (player instanceof ServerPlayer server && !stack.isEmpty()) {
                server.sendOverlayMessage(Component.translatable("siftec.burner.fuel"));
                return InteractionResult.SUCCESS;
            }
            return InteractionResult.TRY_WITH_EMPTY_HAND;
        }
        if (player instanceof ServerPlayer server) {
            burner.addFuel(stack);
            server.sendOverlayMessage(burner.status());
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer server && level.getBlockEntity(pos) instanceof HubBurnerBlockEntity burner) {
            if (player.isShiftKeyDown() && burner.tripped && PowerGrids.reset(server.level(), burner)) {
                server.sendOverlayMessage(Component.translatable("siftec.power.fuse_reset"));
            } else {
                server.sendOverlayMessage(burner.status());
            }
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(Facing.FACING);
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return Facing.rotate(state, rotation);
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return Facing.mirror(state, mirror);
    }
}
