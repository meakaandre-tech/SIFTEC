package com.meakaandre.siftec.sim;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The simulation's names for things, so its state is plain strings and numbers that any thread can read: an item
 * without components is its id ("minecraft:iron_ingot"); one with components (a package, a named item) is "@" and the
 * item as JSON. A fluid is "fluid:" and its id. Turning keys into items and back is done on the server thread only.
 */
public final class Keys {
    private static final Map<ItemVariant, String> BY_VARIANT = new ConcurrentHashMap<>();
    private static final Map<String, ItemVariant> BY_KEY = new ConcurrentHashMap<>();
    private static final Map<String, Integer> MAX = new ConcurrentHashMap<>();
    private static volatile @Nullable MinecraftServer server;

    private Keys() {
    }

    static void serverStarted(MinecraftServer s) {
        server = s;
    }

    static void serverStopped() {
        server = null;
        BY_VARIANT.clear();
        BY_KEY.clear();
    }

    public static String of(ItemStack stack) {
        return stack.isEmpty() ? "" : of(ItemVariant.of(stack));
    }

    public static String of(Item item) {
        return of(ItemVariant.of(item));
    }

    public static String of(ItemVariant v) {
        if (v.isBlank()) return "";
        String known = BY_VARIANT.get(v);
        if (known != null) return known;
        String key;
        if (!v.hasComponents()) {
            key = BuiltInRegistries.ITEM.getKey(v.getItem()).toString();
        } else {
            MinecraftServer s = server;
            JsonElement json = s == null ? null
                : ItemVariant.CODEC.encodeStart(s.registryAccess().createSerializationContext(JsonOps.INSTANCE), v).result().orElse(null);
            key = json == null ? BuiltInRegistries.ITEM.getKey(v.getItem()).toString() : "@" + json;
        }
        BY_VARIANT.put(v, key);
        BY_KEY.put(key, v);
        MAX.put(key, v.toStack().getMaxStackSize());
        return key;
    }

    public static ItemVariant variant(String key) {
        if (key.isEmpty()) return ItemVariant.blank();
        ItemVariant known = BY_KEY.get(key);
        if (known != null) return known;
        ItemVariant v;
        if (key.startsWith("@")) {
            MinecraftServer s = server;
            v = s == null ? ItemVariant.blank() : ItemVariant.CODEC.parse(s.registryAccess().createSerializationContext(JsonOps.INSTANCE),
                JsonParser.parseString(key.substring(1))).result().orElse(ItemVariant.blank());
        } else {
            Item item = BuiltInRegistries.ITEM.getOptional(Identifier.tryParse(key) == null ? Identifier.withDefaultNamespace("air") : Identifier.tryParse(key)).orElse(Items.AIR);
            v = item == Items.AIR ? ItemVariant.blank() : ItemVariant.of(item);
        }
        if (!v.isBlank()) {
            BY_KEY.put(key, v);
            BY_VARIANT.putIfAbsent(v, key);
            MAX.put(key, v.toStack().getMaxStackSize());
        }
        return v;
    }

    public static ItemStack stack(String key, long count) {
        ItemVariant v = variant(key);
        return v.isBlank() || count <= 0 ? ItemStack.EMPTY : v.toStack((int) Math.min(Integer.MAX_VALUE, count));
    }

    /** The most of one key a stack holds (64 for anything never seen on the server thread). */
    public static int max(String key) {
        Integer m = MAX.get(key);
        return m == null ? 64 : m;
    }

    public static boolean isFluid(String key) {
        return key.startsWith("fluid:");
    }

    public static String fluid(Fluid fluid) {
        Fluid still = fluid instanceof FlowingFluid flowing ? flowing.getSource() : fluid;
        return "fluid:" + BuiltInRegistries.FLUID.getKey(still);
    }

    public static String fluid(FluidVariant v) {
        return fluid(v.getFluid());
    }

    public static Fluid fluidOf(String key) {
        if (!isFluid(key)) return Fluids.EMPTY;
        Identifier id = Identifier.tryParse(key.substring(6));
        return id == null ? Fluids.EMPTY : BuiltInRegistries.FLUID.getOptional(id).orElse(Fluids.EMPTY);
    }

    /** The plain id of an item key (the item's id, whatever its components). */
    public static String id(String key) {
        if (!key.startsWith("@")) return key;
        ItemVariant v = variant(key);
        return v.isBlank() ? key : BuiltInRegistries.ITEM.getKey(v.getItem()).toString();
    }
}
