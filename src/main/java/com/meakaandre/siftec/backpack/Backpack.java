package com.meakaandre.siftec.backpack;

import com.meakaandre.siftec.Siftec;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * The player's backpack is the Traveler's Backpack each player is given (see
 * {@link com.meakaandre.siftec.compat.TravelersBackpackCompat}). What is left here: the store of the old SIFTEC
 * backpack rows, still registered so old saves load, whose items are moved out the next time the player joins; and
 * the hook through which payments and radiation see what the worn backpack holds.
 */
public final class Backpack {
    /** How many slots the old backpack rows had. */
    public static final int SIZE = 36;

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

    /** The old backpack rows' items. Kept only to read old saves: {@link #migrate} empties it. */
    public static final AttachmentType<Contents> CONTENTS = AttachmentRegistry.create(Siftec.id("backpack"),
        builder -> builder.persistent(Contents.CODEC).copyOnDeath().initializer(Contents::new));

    /** What the player's worn backpack holds, for paying milestones and for radiation. Set by the compat layer. */
    public static volatile Function<ServerPlayer, List<Container>> worn = player -> List.of();

    private Backpack() {
    }

    public static void register() {
        // without Traveler's Backpack the old items go to the inventory (with it, the compat layer does this after
        // making sure the player wears their backpack)
        if (!FabricLoader.getInstance().isModLoaded("travelersbackpack")) ServerPlayerEvents.JOIN.register(player -> migrate(player, stack -> stack));
    }

    /** The containers of the player's worn backpack: none, or the Traveler's Backpack storage. */
    public static List<Container> containers(ServerPlayer player) {
        return worn.apply(player);
    }

    /**
     * Moves every item of the old backpack rows out: into {@code into} (which returns what did not fit), then the
     * inventory, then onto the ground at the player's feet with a chat message. The old store is removed. Returns how
     * many stacks were moved.
     */
    public static int migrate(ServerPlayer player, UnaryOperator<ItemStack> into) {
        Contents old = player.getAttached(CONTENTS);
        if (old == null) return 0;
        int moved = 0;
        boolean dropped = false;
        for (int i = 0; i < SIZE; i++) {
            ItemStack stack = old.items.get(i);
            if (stack.isEmpty()) continue;
            old.items.set(i, ItemStack.EMPTY);
            moved++;
            ItemStack left = into.apply(stack);
            if (left.isEmpty()) continue;
            player.getInventory().add(left);
            if (left.isEmpty()) continue;
            player.getInventory().placeItemBackInInventory(left, Prediction.SERVER_ONLY);
            dropped = true;
        }
        player.removeAttached(CONTENTS);
        if (moved > 0) {
            Siftec.LOGGER.info("SIFTEC: moved {} stacks out of {}'s old backpack rows{}", moved, player.getGameProfile().name(), dropped ? " (some dropped)" : "");
            player.sendSystemMessage(Component.translatable("siftec.backpack.migrated", moved).withStyle(ChatFormatting.GOLD));
            if (dropped) player.sendSystemMessage(Component.translatable("siftec.backpack.dropped").withStyle(ChatFormatting.RED));
        }
        return moved;
    }
}
