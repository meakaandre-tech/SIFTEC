package com.meakaandre.siftec.hub;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.List;

/** A HUB milestone (tier 0 to 9) or a wormhole phase (tier -1). */
public record Milestone(String id, int tier, int index, List<Cost> cost, int seconds, List<Identifier> items, List<String> tokens) {
    public record Cost(Identifier itemId, int count) {
        /** Air when the mod that owns the item is not installed; such costs are skipped. */
        public Item item() {
            return BuiltInRegistries.ITEM.getOptional(itemId).orElse(Items.AIR);
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
