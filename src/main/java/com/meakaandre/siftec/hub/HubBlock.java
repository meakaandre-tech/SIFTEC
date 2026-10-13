package com.meakaandre.siftec.hub;

import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.company.CompanyData;
import com.meakaandre.siftec.prefab.Prefabs;
import com.meakaandre.siftec.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The HUB Terminal (milestones) and the Wormhole Gateway's terminal (phases), each the controller of its whole
 * structure. Each belongs to the company that placed it. Using it opens the milestone screen; sneaking and using it
 * twice picks the whole structure up (an old single-block one as well) and gives the item back.
 */
public class HubBlock extends Block implements EntityBlock {
    public final boolean gateway;
    /** Who sneak-used which terminal last, and when: the second sneak-use within five seconds picks it up. */
    private static final Map<UUID, long[]> ARMED = new HashMap<>();

    public HubBlock(boolean gateway, Properties properties) {
        // a HUB holds land: it cannot be broken, blown up or pushed, or its claim would be left behind
        super(properties.strength(-1.0F, 3600000.0F).noLootTable().pushReaction(net.minecraft.world.level.material.PushReaction.IMMOVEABLE));
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
        Company owner = CompanyData.get(server.level().getServer()).byId(hub.companyId);
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
        Component name = Component.translatable(gateway ? "block.siftec.wormhole_gateway" : "block.siftec.hub");
        if (player.isShiftKeyDown()) {
            long now = level.getGameTime();
            long[] armed = ARMED.get(player.getUUID());
            if (armed == null || armed[0] != pos.asLong() || now - armed[1] > 100) {
                ARMED.put(player.getUUID(), new long[]{pos.asLong(), now});
                server.sendOverlayMessage(Component.translatable("siftec.prefab.dismantle_confirm", name));
                return InteractionResult.SUCCESS;
            }
            ARMED.remove(player.getUUID());
            ServerLevel serverLevel = server.level();
            Prefabs.give(serverLevel, server, pos, Prefabs.dismantle(serverLevel, hub));
            server.sendOverlayMessage(Component.translatable("siftec.prefab.dismantled", name));
            return InteractionResult.SUCCESS;
        }
        // the HUB hands every member of its company a Build Gun, as Satisfactory's HUB does
        if (!gateway && hub.prefab) Prefabs.giveBuildGun(server);
        HubMenu.open(server, mine, pos, gateway);
        return InteractionResult.SUCCESS;
    }

    // the front looks at whoever placed it
    @Override
    protected void createBlockStateDefinition(net.minecraft.world.level.block.state.StateDefinition.Builder<net.minecraft.world.level.block.Block, net.minecraft.world.level.block.state.BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(com.meakaandre.siftec.block.Facing.FACING);
    }

    @Override
    public net.minecraft.world.level.block.state.BlockState getStateForPlacement(net.minecraft.world.item.context.BlockPlaceContext context) {
        return com.meakaandre.siftec.block.Facing.place(super.getStateForPlacement(context), context);
    }

    @Override
    protected net.minecraft.world.level.block.state.BlockState rotate(net.minecraft.world.level.block.state.BlockState state, net.minecraft.world.level.block.Rotation rotation) {
        return com.meakaandre.siftec.block.Facing.rotate(state, rotation);
    }

    @Override
    protected net.minecraft.world.level.block.state.BlockState mirror(net.minecraft.world.level.block.state.BlockState state, net.minecraft.world.level.block.Mirror mirror) {
        return com.meakaandre.siftec.block.Facing.mirror(state, mirror);
    }
}
