package com.meakaandre.siftec.prefab;

import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.company.CompanyData;
import com.meakaandre.siftec.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/** The HUB's Personal Storage Box, added by HUB Upgrade 1. Only the HUB's company can open it. */
public class StorageBoxBlock extends Block implements EntityBlock, PrefabPart {
    public StorageBoxBlock(Properties properties) {
        super(properties);
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new StorageBoxBlockEntity(ModBlockEntities.STORAGE_BOX.get(), pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!(player instanceof ServerPlayer server) || !(level.getBlockEntity(pos) instanceof StorageBoxBlockEntity box)) return InteractionResult.SUCCESS;
        Company owner = box.companyId.isEmpty() ? null : CompanyData.get(server.level().getServer()).byId(box.companyId);
        if (owner != null && owner != Companies.of(server) && !server.hasInfiniteMaterials()) {
            server.sendOverlayMessage(Component.translatable("siftec.hub.not_yours", owner.name));
            return InteractionResult.SUCCESS;
        }
        server.openMenu(new SimpleMenuProvider((id, inventory, p) -> ChestMenu.threeRows(id, inventory, box.items), Component.translatable("block.siftec.personal_storage")));
        return InteractionResult.SUCCESS;
    }
}
