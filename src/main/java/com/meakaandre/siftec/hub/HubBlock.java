package com.meakaandre.siftec.hub;

import com.meakaandre.siftec.block.Facing;
import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
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
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;


/**
 * The HUB Terminal (milestones) and the Wormhole Gateway's terminal (phases), each the controller of its whole
 * structure. Each belongs to the company that placed it. Using it opens the milestone screen; only the Build Gun's
 * dismantle mode takes either down.
 */
public class HubBlock extends Block implements EntityBlock {
    /** Vanilla's bedrock figures: no breaking time, and no explosion takes it. */
    private static final float UNBREAKABLE_HARDNESS = -1.0F, UNBREAKABLE_RESISTANCE = 3600000.0F;
    public final boolean gateway;

    public HubBlock(boolean gateway, Properties properties) {
        // a HUB holds land: it cannot be broken, blown up or pushed, or its claim would be left behind
        super(properties.strength(UNBREAKABLE_HARDNESS, UNBREAKABLE_RESISTANCE).noLootTable()
            .pushReaction(PushReaction.IMMOVEABLE));
        this.gateway = gateway;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new HubBlockEntity(ModBlockEntities.HUB.get(), pos, state);
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide() ? null : (l, p, s, be) -> {
            if (be instanceof HubBlockEntity hub) hub.serverTick();
        };
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!(player instanceof ServerPlayer server)) return InteractionResult.SUCCESS;
        if (!(level.getBlockEntity(pos) instanceof HubBlockEntity hub)) return InteractionResult.SUCCESS;
        Company mine = Companies.of(server);
        Company owner = Companies.byId(server.level().getServer(), hub.companyId);
        if (owner == null) {
            // placed by a machine or left behind by a company that no longer exists: whoever uses it takes it over
            hub.companyId = mine.id;
            hub.setChanged();
            owner = mine;
        }
        if (owner != mine) {
            server.sendOverlayMessage(Component.translatable("siftec.hub.not_yours", owner.name));
            return InteractionResult.SUCCESS;
        }
        if (player.isShiftKeyDown()) {
            // the HUB and the Wormhole Gateway are Build Gun buildings: only the gun's dismantle mode takes them down
            server.sendOverlayMessage(gateway
                ? Component.translatableWithFallback("siftec.prefab.gateway_gun", "Take the Wormhole Gateway down with the Build Gun's dismantle mode (X): its parts and progress are kept")
                : Component.translatable("siftec.prefab.hub_gun"));
            return InteractionResult.SUCCESS;
        }
        HubMenu.open(server, mine, pos, gateway);
        return InteractionResult.SUCCESS;
    }

    // the front looks at whoever placed it
    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(Facing.FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return Facing.place(super.getStateForPlacement(context), context);
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
