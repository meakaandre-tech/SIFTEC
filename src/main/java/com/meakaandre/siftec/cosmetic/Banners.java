package com.meakaandre.siftec.cosmetic;

import com.google.gson.JsonParser;
import com.meakaandre.siftec.company.Company;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;

/**
 * The company banner (the AWESOME Shop's Company Banner Designer): a banner a member designs at a loom and uses on the
 * Shop's entry becomes the company's; any member then takes copies of it from the Shop, named for the company.
 */
public final class Banners {
    private Banners() {
    }

    /** A banner item as the company keeps it. */
    public static String write(MinecraftServer server, ItemStack banner) {
        RegistryOps<com.google.gson.JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
        return ItemStack.CODEC.encodeStart(ops, banner.copyWithCount(1)).result().map(Object::toString).orElse("");
    }

    /** A copy of the company's banner, named for it; empty if it has none. */
    public static ItemStack read(MinecraftServer server, Company company) {
        if (company.banner.isEmpty()) return ItemStack.EMPTY;
        RegistryOps<com.google.gson.JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
        try {
            ItemStack banner = ItemStack.CODEC.parse(ops, JsonParser.parseString(company.banner)).result().orElse(ItemStack.EMPTY);
            if (!banner.isEmpty()) banner.set(DataComponents.CUSTOM_NAME, Component.translatable("siftec.shop.banner_name", company.name));
            return banner;
        } catch (RuntimeException e) {
            return ItemStack.EMPTY;
        }
    }
}
