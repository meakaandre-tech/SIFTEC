package com.meakaandre.siftec.backpack;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.hub.Milestone;
import com.meakaandre.siftec.hub.Milestones;
import com.meakaandre.siftec.net.ClientState;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Set;

/**
 * The backpack: up to four extra rows of the normal inventory, shown between the main rows and the hotbar in every
 * window that shows the player's inventory (see {@link BackpackRows}). Every "+3 backpack slots" reward opens three
 * more. Items picked up go there once the hotbar and main rows are full. The items are saved with the player; on
 * death they drop like the rest of the inventory unless keepInventory is on.
 */
public final class Backpack {
    /** All the slots there can ever be: four rows under the inventory. */
    public static final int SIZE = 36;
    public static final int PER_REWARD = 3;

    /** The saved contents. A plain holder, changed in place. */
    public static final class Contents {
        public final NonNullList<ItemStack> items = NonNullList.withSize(SIZE, ItemStack.EMPTY);

        /**
         * Saved as a list of {Slot, Item} for the filled slots, so every item keeps its place. Each item is read
         * on its own: one that cannot be read any more (its mod was removed) is logged and skipped, and the
         * rest stay where they were. Older saves were a plain list by position, and still load.
         */
        public static final Codec<Contents> CODEC = new Codec<>() {
            @Override
            public <T> DataResult<Pair<Contents, T>> decode(DynamicOps<T> ops, T input) {
                Contents contents = new Contents();
                DataResult<java.util.stream.Stream<T>> list = ops.getStream(input);
                if (list.isError()) return DataResult.success(Pair.of(contents, ops.empty()));
                List<T> entries = list.getOrThrow().toList();
                for (int i = 0; i < entries.size(); i++) {
                    T entry = entries.get(i);
                    int slot = i;
                    T item = entry;
                    DataResult<T> tagged = ops.get(entry, "Item");
                    if (tagged.isSuccess()) {
                        item = tagged.getOrThrow();
                        slot = ops.get(entry, "Slot").flatMap(ops::getNumberValue).map(Number::intValue).result().orElse(-1);
                    }
                    if (slot < 0 || slot >= SIZE) continue;
                    DataResult<ItemStack> stack = ItemStack.OPTIONAL_CODEC.parse(ops, item);
                    if (stack.isSuccess()) {
                        contents.items.set(slot, stack.getOrThrow());
                    } else {
                        Siftec.LOGGER.warn("SIFTEC: dropped an unreadable item from backpack slot {}: {}", slot, stack.error().map(e -> e.message()).orElse("?"));
                    }
                }
                return DataResult.success(Pair.of(contents, ops.empty()));
            }

            @Override
            public <T> DataResult<T> encode(Contents contents, DynamicOps<T> ops, T prefix) {
                List<T> out = new java.util.ArrayList<>();
                for (int i = 0; i < SIZE; i++) {
                    ItemStack stack = contents.items.get(i);
                    if (stack.isEmpty()) continue;
                    DataResult<T> item = ItemStack.CODEC.encodeStart(ops, stack);
                    if (item.isError()) {
                        Siftec.LOGGER.warn("SIFTEC: could not save the item in backpack slot {}: {}", i, stack);
                        continue;
                    }
                    out.add(ops.createMap(java.util.Map.of(ops.createString("Slot"), ops.createInt(i), ops.createString("Item"), item.getOrThrow())));
                }
                return DataResult.success(ops.createList(out.stream()));
            }
        };
    }

    /** Copied to the respawned player; when keepInventory is off it was emptied onto the ground first, like the inventory. */
    public static final AttachmentType<Contents> CONTENTS = AttachmentRegistry.create(Siftec.id("backpack"),
        builder -> builder.persistent(Contents.CODEC).copyOnDeath().initializer(Contents::new));

    private Backpack() {
    }

    public static void register() {
        BackpackSelfTest.register();
    }

    /** How many slots are open for this player. */
    public static int unlocked(Player player) {
        if (player.hasInfiniteMaterials()) return SIZE;
        if (player instanceof ServerPlayer server) {
            Company company = Companies.of(server);
            return Math.min(SIZE, company.count("backpack") * PER_REWARD);
        }
        return ClientState.backpackSlots;
    }

    /**
     * Puts what it can of {@code stack} into the open backpack slots, server side: first onto stacks of the same
     * item, then (when {@code empties}) into empty slots from the first. Returns whether anything went in.
     */
    public static boolean insert(Player player, ItemStack stack, boolean empties) {
        if (stack.isEmpty() || player.level().isClientSide()) return false;
        NonNullList<ItemStack> items = player.getAttachedOrCreate(CONTENTS).items;
        int open = unlocked(player);
        boolean any = false;
        if (stack.isStackable()) {
            for (int i = 0; i < open && !stack.isEmpty(); i++) {
                ItemStack target = items.get(i);
                if (target.isEmpty() || !ItemStack.isSameItemSameComponents(target, stack)) continue;
                int room = Math.min(target.getMaxStackSize(), 99) - target.getCount();
                if (room <= 0) continue;
                int n = Math.min(room, stack.getCount());
                target.grow(n);
                stack.shrink(n);
                target.setPopTime(5);
                any = true;
            }
        }
        if (empties) {
            for (int i = 0; i < open && !stack.isEmpty(); i++) {
                if (!items.get(i).isEmpty()) continue;
                ItemStack put = stack.split(Math.min(stack.getMaxStackSize(), stack.getCount()));
                put.setPopTime(5);
                items.set(i, put);
                any = true;
            }
        }
        return any;
    }

    /** The same count, worked out on the client from the progress the server sent. */
    public static int unlocked(Set<String> done) {
        int rewards = 0;
        for (String id : done) {
            Milestone m = Milestones.get(id);
            if (m != null && m.tokens().contains("backpack")) rewards++;
        }
        return Math.min(SIZE, rewards * PER_REWARD);
    }
}
