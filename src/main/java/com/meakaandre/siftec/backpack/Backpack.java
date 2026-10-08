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
 * The backpack: extra slots under the normal inventory, shown whenever the inventory is open.
 * Every "+3 backpack slots" reward opens three more. The items are saved with the player and are kept on death.
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

    public static final AttachmentType<Contents> CONTENTS = AttachmentRegistry.create(Siftec.id("backpack"),
        builder -> builder.persistent(Contents.CODEC).copyOnDeath().initializer(Contents::new));

    private Backpack() {
    }

    public static void register() {
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
