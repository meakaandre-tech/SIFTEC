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
    public static final Supplier<Item> JETPACK = item("jetpack", properties -> new Item(properties.stacksTo(1).equippable(net.minecraft.world.entity.EquipmentSlot.CHEST)));
    public static final Supplier<Item> HOVER_PACK = item("hover_pack", properties -> new Item(properties.stacksTo(1).equippable(net.minecraft.world.entity.EquipmentSlot.CHEST)));
    public static final Supplier<Item> PARACHUTE = item("parachute", properties -> new Item(properties.stacksTo(1).equippable(net.minecraft.world.entity.EquipmentSlot.CHEST)));
    public static final Supplier<Item> HAZMAT_SUIT = item("hazmat_suit", properties -> new Item(properties.stacksTo(1).equippable(net.minecraft.world.entity.EquipmentSlot.CHEST)));
    public static final Supplier<Item> GAS_MASK = item("gas_mask", properties -> new Item(properties.stacksTo(1).equippable(net.minecraft.world.entity.EquipmentSlot.HEAD)));
    public static final Supplier<Item> BLADE_RUNNERS = item("blade_runners", properties -> new Item(properties.stacksTo(1).equippable(net.minecraft.world.entity.EquipmentSlot.FEET)));
    public static final Supplier<Item> CARDBOARD_DRONE = item("cardboard_drone", properties -> new Item(properties.stacksTo(1)));
    public static final Supplier<com.meakaandre.siftec.blueprint.BlueprintItem> BLUEPRINT = item("blueprint", properties -> new com.meakaandre.siftec.blueprint.BlueprintItem(properties.stacksTo(1)));
    public static final Supplier<com.meakaandre.siftec.hub.HubPlannerItem> HUB_PLANNER = item("hub_planner", properties -> new com.meakaandre.siftec.hub.HubPlannerItem(properties.stacksTo(1)));
    public static final Supplier<Item> TOXIC_SHOT = item("toxic_shot", Item::new);
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
        for (Map.Entry<String, com.meakaandre.siftec.food.Food.Intrinsic> preserve : com.meakaandre.siftec.food.Food.PRESERVES.entrySet()) {
            item(preserve.getKey(), properties -> new com.meakaandre.siftec.food.PreserveItem(preserve.getValue(), properties.stacksTo(16)
                .food(new net.minecraft.world.food.FoodProperties.Builder().nutrition(6).saturationModifier(0.6f).alwaysEdible().build())));
        }
        for (com.google.gson.JsonElement entry : Milestones.raw().getAsJsonArray("parts")) {
            com.google.gson.JsonObject part = entry.getAsJsonObject();
            String id = part.get("id").getAsString();
            // a part that burns (Biomass, Solid Biofuel and so on): the burn time is data, data/siftec/context_int_provider/<fuel>.json
            ResourceKey<net.minecraft.world.level.storage.loot.providers.number.ints.ContextIntProvider> fuel = part.has("fuel")
                ? ResourceKey.create(Registries.CONTEXT_INT_PROVIDER, Siftec.id(part.get("fuel").getAsString())) : null;
            PARTS.put(id, item(id, properties -> new Item(fuel == null ? properties : properties.cookingFuel(fuel))).get());
        }
    }
}
