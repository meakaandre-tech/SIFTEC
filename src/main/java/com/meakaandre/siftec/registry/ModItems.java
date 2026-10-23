package com.meakaandre.siftec.registry;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.blueprint.BlueprintItem;
import com.meakaandre.siftec.building.BuildGunItem;
import com.meakaandre.siftec.food.Food;
import com.meakaandre.siftec.food.PreserveItem;
import com.meakaandre.siftec.geyser.GypsumItem;
import com.meakaandre.siftec.hub.Milestones;
import com.meakaandre.siftec.item.MamItems;
import com.meakaandre.siftec.item.NodeScannerItem;
import com.meakaandre.siftec.item.ObjectScannerItem;
import com.meakaandre.siftec.sink.CustomizerItem;
import com.zurrtum.create.AllDataComponents;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.JukeboxSong;
import net.minecraft.world.level.storage.loot.providers.number.ints.ContextIntProvider;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/** Registers the pack's items: equipment, parts, foods and the guide's building stand-ins. */
public class ModItems {
    /** How many of one MAM inhaler, or of one preserve, a stack holds. */
    private static final int INHALER_STACK = 16, PRESERVE_STACK = 16;
    /** The Elytra Backpack's durability: a vanilla Elytra's. */
    private static final int ELYTRA_BACKPACK_DURABILITY = 432;
    /** What a preserve feeds: hunger points and saturation modifier. */
    private static final int PRESERVE_NUTRITION = 6;
    private static final float PRESERVE_SATURATION = 0.6f;
    public static final Supplier<NodeScannerItem> NODE_SCANNER = item("node_scanner", properties -> new NodeScannerItem(properties.stacksTo(1)));
    public static final Supplier<ObjectScannerItem> OBJECT_SCANNER = item("object_scanner", properties -> new ObjectScannerItem(properties.stacksTo(1)));
    public static final Supplier<GypsumItem> GYPSUM = item("gypsum", GypsumItem::new);
    public static final Supplier<Item> TOXIC_RESIDUE = item("toxic_residue", Item::new);
    public static final Supplier<Item> JETPACK = item("jetpack", properties -> new Item(properties.stacksTo(1).equippable(EquipmentSlot.CHEST)));
    public static final Supplier<Item> HOVER_PACK = item("hover_pack", properties -> new Item(properties.stacksTo(1).equippable(EquipmentSlot.CHEST)));
    public static final Supplier<Item> PARACHUTE = item("parachute", properties -> new Item(properties.stacksTo(1).equippable(EquipmentSlot.CHEST)));
    public static final Supplier<Item> HAZMAT_SUIT = item("hazmat_suit", properties -> new Item(properties.stacksTo(1).equippable(EquipmentSlot.CHEST)));
    public static final Supplier<Item> GAS_MASK = item("gas_mask", properties -> new Item(properties.stacksTo(1).equippable(EquipmentSlot.HEAD)));
    public static final Supplier<Item> BLADE_RUNNERS = item("blade_runners", properties -> new Item(properties.stacksTo(1).equippable(EquipmentSlot.FEET)));
    public static final Supplier<Item> CARDBOARD_DRONE = item("cardboard_drone", properties -> new Item(properties.stacksTo(1)));
    public static final Supplier<BlueprintItem> BLUEPRINT = item("blueprint", properties -> new BlueprintItem(properties.stacksTo(1)));
    /**
     * The HUB Planner: a plain item that does nothing (the HUB is a structure placed whole), kept registered so
     * planners in old inventories still load; it has no recipe and is not in the creative tab.
     */
    public static final Supplier<Item> HUB_PLANNER = hiddenItem("hub_planner", properties -> new Item(properties.stacksTo(1)));
    /** The Build Gun: places and dismantles every building (see building.BuildGun). Given by the HUB, never crafted. */
    public static final Supplier<BuildGunItem> BUILD_GUN = item("build_gun", properties -> new BuildGunItem(properties.stacksTo(1)));
    public static final Supplier<Item> TOXIC_SHOT = item("toxic_shot", Item::new);
    public static final Supplier<Item> RAW_BAUXITE = item("raw_bauxite", Item::new);
    public static final Supplier<Item> SAM = item("sam", Item::new);
    /** From uranium nodes; feeds the nuclear chain. */
    public static final Supplier<Item> URANIUM = item("uranium", Item::new);
    /** The MAM's inhalers (item/MamItems.java): Protein (Alien Megafauna), Vitamin and Therapeutic (Mycelia), Nutritional (Nutrients). */
    public static final Supplier<Item> PROTEIN_INHALER = item("protein_inhaler",
        properties -> new MamItems.InhalerItem(MamItems.Inhaler.PROTEIN, properties.stacksTo(INHALER_STACK)));
    public static final Supplier<Item> VITAMIN_INHALER = item("vitamin_inhaler",
        properties -> new MamItems.InhalerItem(MamItems.Inhaler.VITAMIN, properties.stacksTo(INHALER_STACK)));
    public static final Supplier<Item> THERAPEUTIC_INHALER = item("therapeutic_inhaler",
        properties -> new MamItems.InhalerItem(MamItems.Inhaler.THERAPEUTIC, properties.stacksTo(INHALER_STACK)));
    public static final Supplier<Item> NUTRITIONAL_INHALER = item("nutritional_inhaler",
        properties -> new MamItems.InhalerItem(MamItems.Inhaler.NUTRITIONAL, properties.stacksTo(INHALER_STACK)));
    /** An Elytra and a Copper Backtank in one chest item (MAM, Mycelia): glides, and holds Create's backtank air. */
    public static final Supplier<Item> ELYTRA_BACKPACK = item("elytra_backpack", properties -> new MamItems.ElytraBackpackItem(
        properties.stacksTo(1).durability(ELYTRA_BACKPACK_DURABILITY).equippable(EquipmentSlot.CHEST)
            .component(DataComponents.GLIDER, Unit.INSTANCE)
            .component(AllDataComponents.BACKTANK_AIR, 0)));

    /** What the AWESOME Sink pays in, and the AWESOME Shop takes. */
    public static final Supplier<Item> FICSIT_COUPON = item("ficsit_coupon", properties -> new Item(properties.stacksTo(64)));
    /** Repaints Build Gun buildings (sink/CustomizerMenu.java); free from the AWESOME Shop. */
    public static final Supplier<CustomizerItem> CUSTOMIZER = item("customizer",
        properties -> new CustomizerItem(properties.stacksTo(1)));
    /** The Xeno-Zapper: a hand stun weapon (equip/Equipment.java), from the Equipment Workshop. */
    public static final Supplier<Item> XENO_ZAPPER = item("xeno_zapper", properties -> new Item(properties.stacksTo(1)));
    /** The AWESOME Shop's cups, held in the hand: nothing but a look. */
    public static final Map<String, Item> CUPS = new LinkedHashMap<>();

    static {
        for (JsonElement e : Milestones.raw().getAsJsonArray("cups")) {
            String id = e.getAsJsonObject().get("id").getAsString();
            CUPS.put(id, item(id, properties -> new Item(properties.stacksTo(1))).get());
        }
    }

    /** The AWESOME Shop's cassette tapes for the Boom Box: each plays one of the game's songs. */
    public static final Map<String, Item> TAPES = new LinkedHashMap<>();

    static {
        for (Map.Entry<String, JsonElement> e : Milestones.raw().getAsJsonObject("tapes").entrySet()) {
            ResourceKey<JukeboxSong> song = ResourceKey.create(Registries.JUKEBOX_SONG, Identifier.parse(e.getValue().getAsString()));
            TAPES.put(e.getKey(), item(e.getKey(), properties -> new Item(properties.stacksTo(1).jukeboxPlayable(song))).get());
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

    /**
     * The guide's stand-ins for the Build Gun's buildings and vehicles, by building id: an item
     * {@code siftec:building_<id>} for each, that its Ponder scene, JEI and the Ponder index show. It has no recipe, does
     * nothing and is not in the creative tab; the Build Gun is still the only way to put a building down.
     */
    public static final Map<String, Item> BUILDINGS = new LinkedHashMap<>();

    static {
        for (JsonElement e : Milestones.raw().getAsJsonArray("buildings")) {
            JsonObject b = e.getAsJsonObject();
            if (b.has("dev") && b.get("dev").getAsBoolean()) continue;
            String id = b.get("id").getAsString();
            BUILDINGS.put(id, hiddenItem("building_" + id, properties -> new Item(properties.stacksTo(1))).get());
        }
    }

    public static void register() {
        for (Map.Entry<String, Food.Intrinsic> preserve : Food.PRESERVES.entrySet()) {
            item(preserve.getKey(), properties -> new PreserveItem(preserve.getValue(), properties.stacksTo(PRESERVE_STACK)
                .food(new FoodProperties.Builder().nutrition(PRESERVE_NUTRITION).saturationModifier(PRESERVE_SATURATION)
                    .alwaysEdible().build())));
        }
        for (JsonElement entry : Milestones.raw().getAsJsonArray("parts")) {
            JsonObject part = entry.getAsJsonObject();
            String id = part.get("id").getAsString();
            // a part that burns (Biomass, Solid Biofuel and so on): the burn time is data, data/siftec/context_int_provider/<fuel>.json
            ResourceKey<ContextIntProvider> fuel = part.has("fuel")
                ? ResourceKey.create(Registries.CONTEXT_INT_PROVIDER, Siftec.id(part.get("fuel").getAsString())) : null;
            PARTS.put(id, item(id, properties -> new Item(fuel == null ? properties : properties.cookingFuel(fuel))).get());
        }
    }
}
