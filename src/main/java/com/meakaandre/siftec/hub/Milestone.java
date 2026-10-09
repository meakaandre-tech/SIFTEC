package com.meakaandre.siftec.hub;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/** A HUB milestone (tier 0 to 9), a wormhole phase (tier -1) or a MAM research node (in a tree). */
public record Milestone(String id, int tier, int index, List<Cost> cost, int seconds, List<Identifier> items, List<String> tokens,
                        String tree, List<String> needs) {
    public Milestone(String id, int tier, int index, List<Cost> cost, int seconds, List<Identifier> items, List<String> tokens) {
        this(id, tier, index, cost, seconds, items, tokens, "", List.of());
    }

    /** A MAM research node, not a HUB milestone. */
    public boolean isResearch() {
        return !tree.isEmpty();
    }

    /** One line of a cost: an item id, or a tag written "#namespace:path" for "any of these". */
    public record Cost(String key, int count) {
        public boolean isTag() {
            return key.startsWith("#");
        }

        private Item item() {
            return BuiltInRegistries.ITEM.getOptional(Identifier.parse(key)).orElse(Items.AIR);
        }

        private TagKey<Item> tag() {
            return TagKey.create(Registries.ITEM, Identifier.parse(key.substring(1)));
        }

        /** False when the mod that owns the item is not installed; such costs are skipped. */
        public boolean present() {
            return isTag() || item() != Items.AIR;
        }

        public boolean matches(ItemStack stack) {
            if (stack.isEmpty()) return false;
            return isTag() ? stack.is(tag()) : stack.is(item());
        }

        /** What to draw for the cost: the item, or for a tag one of its items in turn (a new one every second). */
        public ItemStack icon() {
            if (!isTag()) return new ItemStack(item());
            List<Item> items = new java.util.ArrayList<>();
            for (net.minecraft.core.Holder<Item> holder : BuiltInRegistries.ITEM.getTagOrEmpty(tag())) items.add(holder.value());
            if (items.isEmpty()) return new ItemStack(Items.BARRIER);
            return new ItemStack(items.get((int) (System.currentTimeMillis() / 1000 % items.size())));
        }

        public Component label() {
            if (!isTag()) return new ItemStack(item()).getItemName();
            String path = key.substring(key.indexOf(':') + 1);
            return Component.translatableWithFallback("siftec.tag." + path.replace('/', '.'), "any " + path.substring(path.lastIndexOf('/') + 1).replace('_', ' '));
        }

        /** The inventory and, on the server, the backpack. */
        private static java.util.List<net.minecraft.world.Container> containers(Inventory inventory) {
            if (!(inventory.player instanceof net.minecraft.server.level.ServerPlayer)) return java.util.List.of(inventory);
            return java.util.List.of(inventory, new com.meakaandre.siftec.backpack.BackpackContainer(inventory.player));
        }

        /** How many matching items the player is carrying, backpack included. */
        public int carried(Inventory inventory) {
            int n = 0;
            for (net.minecraft.world.Container container : containers(inventory)) {
                for (int i = 0; i < container.getContainerSize(); i++) {
                    if (matches(container.getItem(i))) n += container.getItem(i).getCount();
                }
            }
            return n;
        }

        /** Takes up to {@code amount} matching items out of the inventory, then the backpack, and says how many it got. */
        public int take(Inventory inventory, int amount) {
            int left = amount;
            for (net.minecraft.world.Container container : containers(inventory)) {
                for (int i = 0; i < container.getContainerSize() && left > 0; i++) {
                    ItemStack stack = container.getItem(i);
                    if (!matches(stack)) continue;
                    int take = Math.min(left, stack.getCount());
                    container.removeItem(i, take);
                    left -= take;
                }
            }
            return amount - left;
        }
    }

    public boolean isPhase() {
        return tier < 0;
    }

    public Component name() {
        return Component.translatable("siftec.milestone." + id);
    }

    public Component unlockText() {
        return Component.translatable("siftec.milestone." + id + ".unlocks");
    }
}
