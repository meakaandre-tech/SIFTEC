package com.meakaandre.siftec.prefab;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** The HUB's Craft Bench: a crafting grid, as a crafting table has, that stays open while the player is near it. */
public class CraftBenchBlock extends Block implements PrefabPart {
    public CraftBenchBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide()) {
            ContainerLevelAccess access = ContainerLevelAccess.create(level, pos);
            Block bench = this;
            player.openMenu(new SimpleMenuProvider((id, inventory, p) -> new CraftingMenu(id, inventory, access) {
                @Override
                public boolean stillValid(Player who) {
                    // a crafting table's menu only stays open at a crafting table; this one at the bench
                    return stillValid(access, who, bench);
                }
            }, Component.translatable("block.siftec.craft_bench")));
        }
        return InteractionResult.SUCCESS;
    }
}
