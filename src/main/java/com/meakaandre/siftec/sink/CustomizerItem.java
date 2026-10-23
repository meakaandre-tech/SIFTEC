package com.meakaandre.siftec.sink;

import com.meakaandre.siftec.building.BuildingIndex;
import com.meakaandre.siftec.building.ControllerBlockEntity;
import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.cosmetic.GoldenCart;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * The Customizer: used on any block of a Build Gun building the company owns, it opens the paint window for that
 * building (swatches, finishes and casing skins the company has bought in the AWESOME Shop).
 */
public class CustomizerItem extends Item {
    public CustomizerItem(Properties properties) {
        super(properties);
    }

    /** Used in the air: the helmet skins and suit trims for what the player wears. */
    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (player instanceof ServerPlayer server) OutfitMenu.open(server);
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (!(context.getPlayer() instanceof ServerPlayer player)) return InteractionResult.SUCCESS;
        // a Cart Assembler: the Golden Cart Assembler skin, on or off
        if (GoldenCart.is(context.getLevel().getBlockState(context.getClickedPos()))) {
            if (!Shop.owns(player, Companies.of(player), "golden_cart")) {
                player.sendOverlayMessage(Component.translatable("siftec.customizer.locked"));
            } else {
                GoldenCart.toggle(context.getLevel(), context.getClickedPos());
                player.sendOverlayMessage(Component.translatable("siftec.customizer.golden_cart"));
            }
            return InteractionResult.SUCCESS;
        }
        BlockPos controller = BuildingIndex.controllerOf(context.getLevel(), context.getClickedPos());
        if (controller == null || !(context.getLevel().getBlockEntity(controller) instanceof ControllerBlockEntity c)) {
            player.sendOverlayMessage(Component.translatable("siftec.customizer.not_building"));
            return InteractionResult.SUCCESS;
        }
        open(player, c);
        return InteractionResult.SUCCESS;
    }

    /** Opens the paint window for a building, if the player's company may change it. */
    public static void open(ServerPlayer player, ControllerBlockEntity c) {
        if (!player.hasInfiniteMaterials() && !c.companyId.isEmpty() && !Companies.owns(Companies.of(player), c.companyId)) {
            player.sendOverlayMessage(Component.translatable("siftec.build_gun.not_yours"));
            return;
        }
        CustomizerMenu.open(player, Companies.of(player), c.getBlockPos());
    }
}
