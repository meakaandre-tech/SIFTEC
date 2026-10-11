package com.meakaandre.siftec.prefab;

import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.hub.Locks;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

/**
 * The HUB and Wormhole Gateway items. Held, they show the whole structure where the player looks; using them puts
 * it down there (sneak and use to turn it a quarter). A survival player uses the item up.
 */
public class PrefabItem extends BlockItem {
    public final Prefabs.Kind kind;

    public PrefabItem(Prefabs.Kind kind, Block block, Properties properties) {
        super(block, properties);
        this.kind = kind;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null) return InteractionResult.FAIL;
        return build(context.getLevel(), player, context.getHand());
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        return build(level, player, hand);
    }

    private InteractionResult build(Level level, Player player, InteractionHand hand) {
        if (player.isShiftKeyDown()) {
            int turns = Prefabs.turn(player);
            if (player instanceof ServerPlayer server) server.sendOverlayMessage(Component.translatable("siftec.prefab.turned", turns * 90));
            return InteractionResult.SUCCESS;
        }
        if (!(player instanceof ServerPlayer server) || !(level instanceof ServerLevel serverLevel)) return InteractionResult.SUCCESS;
        if (Companies.isFake(server)) return InteractionResult.FAIL;
        ItemStack stack = player.getItemInHand(hand);
        if (!Locks.allowed(player, stack)) {
            server.sendOverlayMessage(Component.translatable("siftec.lock.item", Locks.lockOf(stack.getItem()).name()));
            return InteractionResult.FAIL;
        }
        Prefabs.Spot spot = Prefabs.aim(player, kind);
        if (spot == null) {
            server.sendOverlayMessage(Component.translatable("siftec.prefab.aim", Prefabs.RANGE));
            return InteractionResult.FAIL;
        }
        Company company = Companies.of(server);
        Prefabs.Check check = Prefabs.place(serverLevel, spot, company, server);
        if (!check.ok()) {
            server.sendOverlayMessage(check.message());
            server.containerMenu.broadcastFullState();
            return InteractionResult.FAIL;
        }
        if (!player.hasInfiniteMaterials()) stack.shrink(1);
        return InteractionResult.SUCCESS;
    }
}
