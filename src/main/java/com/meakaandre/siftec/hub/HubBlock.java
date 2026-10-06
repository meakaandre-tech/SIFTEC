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
        }
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
        HubMenu.open(server, mine, gateway);
        return InteractionResult.SUCCESS;
    }
}
