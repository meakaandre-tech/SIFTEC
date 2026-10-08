package com.meakaandre.siftec.hub;

import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.company.CompanyData;
import com.meakaandre.siftec.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/** The HUB (milestones) and the Wormhole Gateway (phases). Each belongs to the company of whoever placed it. */
public class HubBlock extends Block implements EntityBlock {
    public final boolean gateway;

    public HubBlock(boolean gateway, Properties properties) {
        super(properties);
        this.gateway = gateway;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new HubBlockEntity(ModBlockEntities.HUB.get(), pos, state);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (placer instanceof ServerPlayer player && level.getBlockEntity(pos) instanceof HubBlockEntity hub) {
            hub.companyId = Companies.of(player).id;
            hub.setChanged();
            // a HUB claims the land around it; the Gateway does not
            if (!gateway && level instanceof net.minecraft.server.level.ServerLevel server) {
                int chunks = com.meakaandre.siftec.claim.Claims.claim(server, Companies.of(player), pos, com.meakaandre.siftec.claim.Claims.HUB_RADIUS, false);
                player.sendOverlayMessage(Component.translatable("siftec.claim.hub", chunks));
            }
        }
    }

    /** The HUB Planner, used on the HUB, sets the building's box and reports on it. */
    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, net.minecraft.world.InteractionHand hand, BlockHitResult hit) {
        if (gateway || !(stack.getItem() instanceof HubPlannerItem)) return InteractionResult.TRY_WITH_EMPTY_HAND;
        if (!(player instanceof ServerPlayer server) || !(level.getBlockEntity(pos) instanceof HubBlockEntity hub)) return InteractionResult.SUCCESS;
        BlockPos[] box = HubPlannerItem.box(stack);
        if (box == null) {
            server.sendOverlayMessage(Component.translatable("siftec.planner.how"));
            return InteractionResult.SUCCESS;
        }
        hub.boxMin = box[0];
        hub.boxMax = box[1];
        hub.setChanged();
        HubBuilding.Result result = hub.measure();
        Company company = Companies.of(server);
        int tier = Math.min(Milestones.TIERS - 1, Math.max(0, result.builtTier() + 1));
        if (result.builtTier() == Milestones.TIERS - 1) tier = Milestones.TIERS - 1;
        server.sendSystemMessage(Component.translatable("siftec.building.title", result.builtTier() < 0 ? Component.translatable("siftec.building.none") : Component.literal("Tier " + result.builtTier())));
        for (Component line : result.report(tier)) server.sendSystemMessage(line);
        return InteractionResult.SUCCESS;
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
        HubMenu.open(server, mine, gateway, gateway || server.hasInfiniteMaterials() ? Milestones.TIERS : hub.builtTier());
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
