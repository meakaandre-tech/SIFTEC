package com.meakaandre.siftec.hub;

import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.food.Food;
import com.meakaandre.siftec.net.ClientState;
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
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything is locked until a milestone unlocks it. A locked item cannot be taken out of a crafting grid
 * and a locked block cannot be placed, by anyone whose company has not finished the milestone.
 * Players in creative mode are never stopped.
 */
public final class Locks {
    private static final Map<Identifier, Milestone> BY_ITEM = new HashMap<>();
    /** A tier no HUB has, for the milestone that locks things for good. */
    private static final int DISABLED_TIER = 99;
    /** Things the pack switches off for good. No company can ever finish this one. */
    public static final Milestone DISABLED = new Milestone("disabled", DISABLED_TIER, 0, List.of(), 0, List.of(), List.of());

    private Locks() {
    }

    public static void register() {
        for (Milestone m : Milestones.all()) {
            for (Identifier item : m.items()) BY_ITEM.putIfAbsent(item, m);
        }
        // the Nutrients research's tokens: what each holds back needs the research that carries it
        for (Map.Entry<String, List<String>> e : Food.tokenItems().entrySet()) {
            Milestone carrier = carrier(e.getKey());
            if (carrier == null) continue;
            for (String id : e.getValue()) BY_ITEM.putIfAbsent(Identifier.parse(id), carrier);
        }
        for (Identifier item : Milestones.disabled()) BY_ITEM.put(item, DISABLED);
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            ItemStack stack = player.getItemInHand(hand);
            if (!(stack.getItem() instanceof BlockItem) || allowed(player, stack)) return InteractionResult.PASS;
            if (player instanceof ServerPlayer server) {
                tellLocked(server, stack);
                // the client has already drawn the block; put its inventory and view right again
                server.containerMenu.broadcastFullState();
            }
            return InteractionResult.FAIL;
        });
    }

    /** The milestone or research node that carries a token, such as "food:preserves"; null if none does. */
    public static @Nullable Milestone carrier(String token) {
        for (Milestone m : Milestones.all()) if (m.tokens().contains(token)) return m;
        return null;
    }

    /** The milestone or research node that unlocks this item, or null when nothing locks it. */
    public static @Nullable Milestone lockOf(Item item) {
        return BY_ITEM.get(BuiltInRegistries.ITEM.getKey(item));
    }

    /**
     * Tells the player, over the hotbar, that this milestone or research node has to be done first. A missing lock
     * (nothing locks the item, say after a data reload) says only that the item is locked, never fails.
     */
    public static void tellLocked(ServerPlayer player, @Nullable Milestone lock) {
        player.sendOverlayMessage(lock == null ? Component.translatable("siftec.lock.unknown") : Component.translatable("siftec.lock.item", lock.name()));
    }

    /** {@link #tellLocked(ServerPlayer, Milestone)} for whatever locks this stack's item. */
    public static void tellLocked(ServerPlayer player, ItemStack stack) {
        tellLocked(player, lockOf(stack.getItem()));
    }

    /**
     * True, with the reason shown to the player, if the player's company may not use this stack yet (see
     * {@link #allowed}); false if it may, so a caller just stops on true.
     */
    public static boolean refused(ServerPlayer player, ItemStack stack) {
        if (allowed(player, stack)) return false;
        tellLocked(player, stack);
        return true;
    }

    /** False, with the reason shown to the player, if the player's company may not take this item out of a result slot. */
    public static boolean mayTake(Player player, ItemStack stack) {
        if (allowed(player, stack)) return true;
        Milestone lock = lockOf(stack.getItem());
        if (player instanceof ServerPlayer server && lock != null) {
            tellLocked(server, lock);
        }
        return false;
    }

    /** Checked on both sides; the client uses the copy of its company's progress the server sent. */
    public static boolean allowed(Player player, ItemStack stack) {
        if (stack.isEmpty() || player.hasInfiniteMaterials()) return true;
        Milestone lock = lockOf(stack.getItem());
        if (lock == null) return true;
        if (player instanceof ServerPlayer server) return Companies.of(server).has(lock.id());
        Set<String> done = ClientState.done;
        return done == null || done.contains(lock.id());
    }
}
