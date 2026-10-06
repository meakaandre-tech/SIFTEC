package com.meakaandre.siftec.hub;

import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.company.Companies;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;

/**
 * Everything is locked until a milestone unlocks it. A locked item cannot be taken out of a crafting grid
 * and a locked block cannot be placed, by anyone whose company has not finished the milestone.
 * Players in creative mode are never stopped.
 */
public final class Locks {
    private static final Map<Identifier, Milestone> BY_ITEM = new HashMap<>();

    private Locks() {
    }

    public static void register() {
        for (Milestone m : Milestones.all()) {
            for (Identifier item : m.items()) BY_ITEM.putIfAbsent(item, m);
        }
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            ItemStack stack = player.getItemInHand(hand);
            if (!(stack.getItem() instanceof BlockItem) || allowed(player, stack)) return InteractionResult.PASS;
            if (player instanceof ServerPlayer server) {
                server.sendOverlayMessage(Component.translatable("siftec.lock.item", lockOf(stack.getItem()).name()));
                // the client has already drawn the block; put its inventory and view right again
                server.containerMenu.broadcastFullState();
            }
            return InteractionResult.FAIL;
        });
    }

    public static Milestone lockOf(Item item) {
        return BY_ITEM.get(BuiltInRegistries.ITEM.getKey(item));
    }

    /** The client does not know company progress, so it always says yes and the server has the last word. */
    public static boolean allowed(Player player, ItemStack stack) {
        if (stack.isEmpty() || !(player instanceof ServerPlayer server) || server.hasInfiniteMaterials()) return true;
        Milestone lock = lockOf(stack.getItem());
        if (lock == null) return true;
        Company company = Companies.of(server);
        return company.has(lock.id());
    }
}
