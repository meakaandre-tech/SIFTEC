package com.meakaandre.siftec.registry;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.geyser.GypsumItem;
import com.meakaandre.siftec.item.NodeScannerItem;
import com.meakaandre.siftec.item.ObjectScannerItem;
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
    /**
     * The HUB Planner of the old HUB building rule. The rule is gone (the HUB is now a structure placed whole), but the
     * id stays registered as a plain item that does nothing, so planners in old inventories still load; it has no
     * recipe and is not in the creative tab.
     */
    public static final Supplier<Item> HUB_PLANNER = hiddenItem("hub_planner", properties -> new Item(properties.stacksTo(1)));
    /** The Build Gun: places and dismantles every building (see building.BuildGun). Given by the HUB, never crafted. */
    public static final Supplier<com.meakaandre.siftec.building.BuildGunItem> BUILD_GUN = item("build_gun", properties -> new com.meakaandre.siftec.building.BuildGunItem(properties.stacksTo(1)));
    public static final Supplier<Item> TOXIC_SHOT = item("toxic_shot", Item::new);
    public static final Supplier<Item> RAW_BAUXITE = item("raw_bauxite", Item::new);
    public static final Supplier<Item> SAM = item("sam", Item::new);
    /** From uranium nodes; work package 4 wires it into the nuclear chain. */
    public static final Supplier<Item> URANIUM = item("uranium", Item::new);
    /** The MAM's inhalers (item/MamItems.java): Protein (Alien Megafauna), Vitamin and Therapeutic (Mycelia), Nutritional (Nutrients). */
    public static final Supplier<Item> PROTEIN_INHALER = item("protein_inhaler",
        properties -> new com.meakaandre.siftec.item.MamItems.InhalerItem(com.meakaandre.siftec.item.MamItems.Inhaler.PROTEIN, properties.stacksTo(16)));
    public static final Supplier<Item> VITAMIN_INHALER = item("vitamin_inhaler",
        properties -> new com.meakaandre.siftec.item.MamItems.InhalerItem(com.meakaandre.siftec.item.MamItems.Inhaler.VITAMIN, properties.stacksTo(16)));
    public static final Supplier<Item> THERAPEUTIC_INHALER = item("therapeutic_inhaler",
        properties -> new com.meakaandre.siftec.item.MamItems.InhalerItem(com.meakaandre.siftec.item.MamItems.Inhaler.THERAPEUTIC, properties.stacksTo(16)));
    public static final Supplier<Item> NUTRITIONAL_INHALER = item("nutritional_inhaler",
        properties -> new com.meakaandre.siftec.item.MamItems.InhalerItem(com.meakaandre.siftec.item.MamItems.Inhaler.NUTRITIONAL, properties.stacksTo(16)));
    /** An Elytra and a Copper Backtank in one chest item (MAM, Mycelia): glides, and holds Create's backtank air. */
    public static final Supplier<Item> ELYTRA_BACKPACK = item("elytra_backpack", properties -> new com.meakaandre.siftec.item.MamItems.ElytraBackpackItem(
        properties.stacksTo(1).durability(432).equippable(net.minecraft.world.entity.EquipmentSlot.CHEST)
            .component(net.minecraft.core.component.DataComponents.GLIDER, net.minecraft.util.Unit.INSTANCE)
            .component(com.zurrtum.create.AllDataComponents.BACKTANK_AIR, 0)));

    /** What the AWESOME Sink pays in, and the AWESOME Shop takes. */
    public static final Supplier<Item> FICSIT_COUPON = item("ficsit_coupon", properties -> new Item(properties.stacksTo(64)));
    /** Repaints Build Gun buildings (sink/CustomizerMenu.java); free from the AWESOME Shop. */
    public static final Supplier<com.meakaandre.siftec.sink.CustomizerItem> CUSTOMIZER = item("customizer",
        properties -> new com.meakaandre.siftec.sink.CustomizerItem(properties.stacksTo(1)));
    /** The Xeno-Zapper: a hand stun weapon (equip/Equipment.java), from the Equipment Workshop. */
    public static final Supplier<Item> XENO_ZAPPER = item("xeno_zapper", properties -> new Item(properties.stacksTo(1)));
    /** The AWESOME Shop's cups, held in the hand: nothing but a look. */
    public static final Map<String, Item> CUPS = new LinkedHashMap<>();

    static {
        for (com.google.gson.JsonElement e : Milestones.raw().getAsJsonArray("cups")) {
            String id = e.getAsJsonObject().get("id").getAsString();
            CUPS.put(id, item(id, properties -> new Item(properties.stacksTo(1))).get());
        }
    }

    private static <T extends Item> Supplier<T> hiddenItem(String name, Function<Item.Properties, T> factory) {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, Siftec.id(name));
        T item = Registry.register(BuiltInRegistries.ITEM, key, factory.apply(new Item.Properties().setId(key)));
        return () -> item;
    }

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
