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

        public Component label() {
            if (!isTag()) return new ItemStack(item()).getItemName();
            String path = key.substring(key.indexOf(':') + 1);
            return Component.translatableWithFallback("siftec.tag." + path.replace('/', '.'), "any " + path.substring(path.lastIndexOf('/') + 1).replace('_', ' '));
        }

        /** How many matching items the player is carrying. */
        public int carried(Inventory inventory) {
            int n = 0;
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                if (matches(inventory.getItem(i))) n += inventory.getItem(i).getCount();
            }
            return n;
        }

        /** Takes up to {@code amount} matching items out of the inventory and says how many it got. */
        public int take(Inventory inventory, int amount) {
            int left = amount;
            for (int i = 0; i < inventory.getContainerSize() && left > 0; i++) {
                ItemStack stack = inventory.getItem(i);
                if (!matches(stack)) continue;
                int take = Math.min(left, stack.getCount());
                inventory.removeItem(i, take);
                left -= take;
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
