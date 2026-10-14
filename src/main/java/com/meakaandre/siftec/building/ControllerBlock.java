package com.meakaandre.siftec.building;

import com.meakaandre.siftec.block.Facing;
import com.meakaandre.siftec.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
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
 * A building's control panel. Part of the building like its shell (only the Build Gun takes it away); using it
 * says what the building is doing. On a Constructor, Assembler, Foundry, Refinery, Blender, Manufacturer, Quantum
 * Encoder, Particle Accelerator or Converter, using it with an item sets what it makes (its recipe filter; a fluid's
 * bucket for a fluid),
 * and sneak-using it clears that. Sneak-using a generator or Power Storage whose grid's fuse has blown resets it.
 */
public class ControllerBlock extends Block implements EntityBlock {
    public ControllerBlock(Properties properties) {
        super(properties);
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ControllerBlockEntity(ModBlockEntities.BUILDING_CONTROLLER.get(), pos, state);
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide() ? null : (l, p, s, be) -> {
            if (be instanceof ControllerBlockEntity controller) controller.serverTick();
        };
    }

    /** Says what the building is doing; sneaking, it clears the recipe filter. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer server && level.getBlockEntity(pos) instanceof ControllerBlockEntity controller) {
            BuildingDef def = Buildings.def(controller.buildingId);
            // sneaking at a generator or Power Storage resets its grid's fuse, as Satisfactory's do
            if (def != null && player.isShiftKeyDown() && controller.tripped && mayUse(server, controller)
                && BuildingPower.resetFuse(server.level(), controller)) {
                server.sendOverlayMessage(Component.translatable("siftec.power.fuse_reset"));
                return InteractionResult.SUCCESS;
            }
            if (def != null && player.isShiftKeyDown() && !controller.filter.isEmpty() && mayUse(server, controller)
                && BuildingLogic.setFilter(level, controller, ItemStack.EMPTY)) {
                server.sendOverlayMessage(Component.translatable("siftec.building.filter_cleared", def.name()));
                return InteractionResult.SUCCESS;
            }
            server.sendOverlayMessage(BuildingLogic.status(level, controller));
        }
        return InteractionResult.SUCCESS;
    }

    /** Used with an item: the recipe filter, as a Basin's filter slot takes one. */
    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof ControllerBlockEntity controller)) return InteractionResult.TRY_WITH_EMPTY_HAND;
        BuildingDef def = Buildings.def(controller.buildingId);
        if (def == null || !BuildingLogic.hasFilter(def)) return InteractionResult.TRY_WITH_EMPTY_HAND;
        if (player instanceof ServerPlayer server) {
            if (!mayUse(server, controller)) {
                server.sendOverlayMessage(Component.translatable("siftec.build_gun.not_yours"));
            } else if (BuildingLogic.setFilter(level, controller, stack)) {
                server.sendOverlayMessage(Component.translatable("siftec.building.filter_set", def.name(), stack.getHoverName()));
            } else {
                server.sendOverlayMessage(Component.translatable("siftec.building.filter_refused", def.name()));
            }
        }
        return InteractionResult.SUCCESS;
    }

    /** A building's settings are its company's to change (anyone may look). */
    private static boolean mayUse(ServerPlayer player, ControllerBlockEntity controller) {
        return player.hasInfiniteMaterials() || controller.companyId.isEmpty()
            || com.meakaandre.siftec.company.Companies.of(player).id.equals(controller.companyId);
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
