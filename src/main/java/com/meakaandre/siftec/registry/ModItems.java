package com.meakaandre.siftec.registry;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.geyser.GypsumItem;
import com.meakaandre.siftec.item.NodeScannerItem;
import com.meakaandre.siftec.item.ObjectScannerItem;
import com.meakaandre.siftec.power.PowerLineItem;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;

import com.meakaandre.siftec.hub.Milestones;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

public class ModItems {
    public static final Supplier<NodeScannerItem> NODE_SCANNER = item("node_scanner", properties -> new NodeScannerItem(properties.stacksTo(1)));
    public static final Supplier<PowerLineItem> POWER_LINE = item("power_line", PowerLineItem::new);
    public static final Supplier<ObjectScannerItem> OBJECT_SCANNER = item("object_scanner", properties -> new ObjectScannerItem(properties.stacksTo(1)));
    public static final Supplier<GypsumItem> GYPSUM = item("gypsum", GypsumItem::new);
    public static final Supplier<Item> TOXIC_RESIDUE = item("toxic_residue", Item::new);
    public static final Supplier<Item> RAW_BAUXITE = item("raw_bauxite", Item::new);
    public static final Supplier<Item> SAM = item("sam", Item::new);

    private static <T extends Item> Supplier<T> item(String name, Function<Item.Properties, T> factory) {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, Siftec.id(name));
        T item = Registry.register(BuiltInRegistries.ITEM, key, factory.apply(new Item.Properties().setId(key)));
        ModBlocks.TAB_ITEMS.add(item);
        return () -> item;
    }

    /** The plain parts (Iron Rod, Rotor, Computer and so on), by id. Listed in siftec_content.json. */
    public static final Map<String, Item> PARTS = new LinkedHashMap<>();

    public static void register() {
        for (String id : Milestones.partIds()) {
            PARTS.put(id, item(id, Item::new).get());
        }
    }
}
