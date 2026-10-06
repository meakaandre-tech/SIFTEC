package com.meakaandre.siftec.hub;

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
    /** Things the pack switches off for good. No company can ever finish this one. */
    public static final Milestone DISABLED = new Milestone("disabled", 99, 0, java.util.List.of(), 0, java.util.List.of(), java.util.List.of());

    private Locks() {
    }

    public static void register() {
        for (Milestone m : Milestones.all()) {
            for (Identifier item : m.items()) BY_ITEM.putIfAbsent(item, m);
        }
        for (Identifier item : Milestones.disabled()) BY_ITEM.put(item, DISABLED);
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

    /** Checked on both sides; the client uses the copy of its company's progress the server sent. */
    public static boolean allowed(Player player, ItemStack stack) {
        if (stack.isEmpty() || player.hasInfiniteMaterials()) return true;
        Milestone lock = lockOf(stack.getItem());
        if (lock == null) return true;
        if (player instanceof ServerPlayer server) return Companies.of(server).has(lock.id());
        java.util.Set<String> done = com.meakaandre.siftec.net.ClientState.done;
        return done == null || done.contains(lock.id());
    }
}
