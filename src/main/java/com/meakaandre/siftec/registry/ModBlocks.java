package com.meakaandre.siftec.registry;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.block.ExtractorBlock;
import com.meakaandre.siftec.block.NodeBlock;
import com.meakaandre.siftec.block.NodePadBlock;
import com.meakaandre.siftec.blueprint.DesignerBlock;
import com.meakaandre.siftec.building.BrassBasinBlock;
import com.meakaandre.siftec.building.ControllerBlock;
import com.meakaandre.siftec.building.PortBlock;
import com.meakaandre.siftec.building.TurbineBlock;
import com.meakaandre.siftec.claim.ClaimMarkerBlock;
import com.meakaandre.siftec.collect.Collectible;
import com.meakaandre.siftec.collect.CollectibleBlock;
import com.meakaandre.siftec.cosmetic.BoomBoxBlock;
import com.meakaandre.siftec.cosmetic.ShopSignBlock;
import com.meakaandre.siftec.death.DeathCrateBlock;
import com.meakaandre.siftec.depot.DepotBlock;
import com.meakaandre.siftec.drone.DronePortBlock;
import com.meakaandre.siftec.equip.LandingPadBlock;
import com.meakaandre.siftec.governor.GovernorBlock;
import com.meakaandre.siftec.hub.HubBlock;
import com.meakaandre.siftec.hub.Milestones;
import com.meakaandre.siftec.mam.MamBlock;
import com.meakaandre.siftec.node.NodeLook;
import com.meakaandre.siftec.node.NodeType;
import com.meakaandre.siftec.portal.PortalBlock;
import com.meakaandre.siftec.prefab.HubBedBlock;
import com.meakaandre.siftec.prefab.HubBurnerBlock;
import com.meakaandre.siftec.prefab.PrefabBlock;
import com.meakaandre.siftec.prefab.PrefabItem;
import com.meakaandre.siftec.prefab.Prefabs;
import com.meakaandre.siftec.prefab.StorageBoxBlock;
import com.meakaandre.siftec.sink.DecorBlock;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/** Registers the pack's blocks and their block items: nodes, ores, machines, building parts and decor. */
public class ModBlocks {
    /** Items shown in the creative tab, in registration order. */
    public static final List<Item> TAB_ITEMS = new ArrayList<>();
    /** Hardness and blast resistance of a block nothing breaks or blows up (bedrock's). */
    private static final float UNBREAKABLE_HARDNESS = -1.0F, UNBREAKABLE_RESISTANCE = 3600000.0F;
    /** How far each way from its terminal each Blueprint Designer Mk's cube reaches, in blocks. */
    private static final int DESIGNER_MK1_REACH = 4, DESIGNER_MK2_REACH = 6, DESIGNER_MK3_REACH = 8;

    /** Patches and fluid nodes cannot be broken, pushed or blown up. */
    private static BlockBehaviour.Properties node() {
        return BlockBehaviour.Properties.of().strength(UNBREAKABLE_HARDNESS, UNBREAKABLE_RESISTANCE).noLootTable()
            .sound(SoundType.DEEPSLATE).pushReaction(PushReaction.IMMOVEABLE);
    }

    /** An ore block of SIFTEC's own: dug, dropped and blown up exactly like a vanilla ore (iron ore's hardness). */
    private static BlockBehaviour.Properties ore() {
        return BlockBehaviour.Properties.of().strength(3.0F, 3.0F).requiresCorrectToolForDrops().sound(SoundType.STONE);
    }

    private static BlockBehaviour.Properties machine() {
        return BlockBehaviour.Properties.of().strength(2.0F, 6.0F).sound(SoundType.METAL).noOcclusion();
    }

    /**
     * Each patch stone's patch: flat unbreakable ground of that stone under a node (see node.NodeLook). Immovable, no
     * item and no loot; Create's drills skip it (in create:non_movable, hardness -1).
     */
    public static final Map<String, Supplier<NodePadBlock>> PATCHES = new LinkedHashMap<>();
    /**
     * The fluid nodes' blocks ({@code nitrogen_node}, and oil's Oil Well). Ore nodes have no block of their own: they
     * are the real ore block (node.NodeLook, node.NodeSites).
     */
    public static final Map<String, Supplier<NodeBlock>> NODES = new LinkedHashMap<>();

    static {
        for (String patch : NodeLook.patches()) {
            PATCHES.put(patch, blockOnly(patch + "_patch", NodePadBlock::new, ModBlocks::node));
        }
        for (NodeLook look : NodeLook.all()) {
            if (!look.fluid() || look.type() == NodeType.OIL) continue;
            NODES.put(look.id(), hidden(look.id() + "_node", p -> new NodeBlock(look, p), ModBlocks::node));
        }
    }

    /** The middle of an oil node: the one block a Pumpjack's pipe counts as an oil deposit. */
    public static final Supplier<NodeBlock> OIL_WELL = hidden("oil_well", properties -> new NodeBlock(NodeLook.byId("oil"), properties), ModBlocks::node);

    static {
        NODES.put("oil", OIL_WELL);
    }

    /**
     * SIFTEC's ore blocks, for the resources neither the game nor Create has an ore of: the node block of those nodes
     * (uranium's is also in its clump). Plain ore blocks: a pickaxe digs them, Fortune adds to the drop, Silk Touch keeps
     * the block.
     */
    public static final Supplier<Block> URANIUM_ORE = hidden("uranium_ore", Block::new, ModBlocks::ore);
    public static final Supplier<Block> BAUXITE_ORE = hidden("bauxite_ore", Block::new, ModBlocks::ore);
    public static final Supplier<Block> SAM_ORE = hidden("sam_ore", Block::new, ModBlocks::ore);
    public static final Supplier<Block> SULFUR_ORE = hidden("sulfur_ore", Block::new, ModBlocks::ore);

    public static final Supplier<ExtractorBlock> RESOURCE_WELL_EXTRACTOR = block("resource_well_extractor", ExtractorBlock::new, ModBlocks::machine);

    /** The HUB's terminal. The HUB has no item: every player builds it with the Build Gun (it is listed in content BUILDINGS). */
    public static final Supplier<HubBlock> HUB = blockOnly("hub", properties -> new HubBlock(false, properties), ModBlocks::machine);
    public static final Supplier<HubBlock> WORMHOLE_GATEWAY = prefab("wormhole_gateway", Prefabs.Kind.GATEWAY, properties -> new HubBlock(true, properties));

    /**
     * The shell blocks of the HUB and Wormhole Gateway structures, by style: Create and vanilla textures (see
     * tools/gen_assets.py, PREFAB). Unbreakable, immovable, no item and no loot, like node pads.
     */
    public static final Map<String, Supplier<PrefabBlock>> PREFAB = new LinkedHashMap<>();

    static {
        // style, the vanilla block whose sound, light and see-through-ness it copies, light level
        Object[][] styles = {{"white", Blocks.CONCRETE.white(), 0}, {"orange", Blocks.CONCRETE.orange(), 0},
            {"metal", Blocks.IRON_BLOCK, 0}, {"floor", Blocks.CONCRETE.lightGray(), 0},
            {"glass", Blocks.GLASS, 0}, {"lamp", Blocks.REDSTONE_LAMP, 15},
            {"step", Blocks.CONCRETE.lightGray(), 0}, {"locker", Blocks.IRON_BLOCK, 0},
            {"railway", Blocks.IRON_BLOCK, 0}, {"coil", Blocks.IRON_BLOCK, 0},
            {"coil_lit", Blocks.AMETHYST_BLOCK, 15}, {"rift", Blocks.CRYING_OBSIDIAN, 15},
            {"foundation", Blocks.CONCRETE.gray(), 0},
            // Polartt's yellow and black hazard stripes, on the HUB's platform edges
            {"hazard_yellow", Blocks.CONCRETE.yellow(), 0}, {"hazard_black", Blocks.CONCRETE.black(), 0},
            // the Wormhole Gateway's energy conduits, and its portal: no collision, see-through, glowing (it swirls like a Nether portal)
            {"conduit", Blocks.IRON_BLOCK, 4}, {"portal", Blocks.NETHER_PORTAL, 15}};
        for (Object[] style : styles) {
            String name = (String) style[0];
            Block like = (Block) style[1];
            int light = (Integer) style[2];
            PREFAB.put(name, blockOnly("prefab_" + name, properties -> new PrefabBlock(name.equals("step"), properties),
                () -> prefabProperties(like, light, name.equals("step") || name.equals("portal"))));
        }
    }

    private static BlockBehaviour.Properties prefabProperties(Block like, int light) {
        return prefabProperties(like, light, false);
    }

    private static BlockBehaviour.Properties prefabProperties(Block like, int light, boolean open) {
        BlockBehaviour.Properties p = BlockBehaviour.Properties.ofFullCopy(like).strength(UNBREAKABLE_HARDNESS, UNBREAKABLE_RESISTANCE)
            .noLootTable().pushReaction(PushReaction.IMMOVEABLE).lightLevel(state -> light);
        return open ? p.noOcclusion() : p;
    }

    public static final Supplier<HubBedBlock> HUB_BED = blockOnly("hub_bed", HubBedBlock::new,
        () -> prefabProperties(Blocks.BED.orange(), 0, true));
    public static final Supplier<StorageBoxBlock> PERSONAL_STORAGE = blockOnly("personal_storage", StorageBoxBlock::new,
        () -> prefabProperties(Blocks.BARREL, 0));
    public static final Supplier<HubBurnerBlock> HUB_BURNER = blockOnly("hub_burner", HubBurnerBlock::new,
        () -> BlockBehaviour.Properties.of().strength(UNBREAKABLE_HARDNESS, UNBREAKABLE_RESISTANCE).noLootTable()
            .sound(SoundType.METAL).noOcclusion().pushReaction(PushReaction.IMMOVEABLE));

    /**
     * The Build Gun's buildings (see building.Buildings): each one's controller and its ports. Like the HUB's shell,
     * unbreakable and immovable; their items exist (not in the tab) only so buildings can be built and saved with a
     * Structure Block.
     */
    public static final Supplier<ControllerBlock> BUILDING_CONTROLLER = hidden("building_controller",
        ControllerBlock::new, () -> prefabProperties(Blocks.IRON_BLOCK, 0));
    public static final Supplier<PortBlock> BUILDING_PORT = hidden("building_port",
        PortBlock::new, () -> prefabProperties(Blocks.IRON_BLOCK, 0));
    /** The turbine inside a generator building, turning its generator coils while it runs (see building.TurbineBlock). */
    public static final Supplier<TurbineBlock> TURBINE = hidden("turbine",
        TurbineBlock::new, () -> prefabProperties(Blocks.IRON_BLOCK, 0).noOcclusion());


    public static final Supplier<DepotBlock> DIMENSIONAL_DEPOT = block("dimensional_depot", DepotBlock::new, ModBlocks::machine);
    public static final Supplier<GovernorBlock> SPEED_GOVERNOR = block("speed_governor", GovernorBlock::new, ModBlocks::machine);
    public static final Supplier<LandingPadBlock> LANDING_PAD = block("landing_pad", LandingPadBlock::new, ModBlocks::machine);
    /** The Drone Port building's terminal: only ever part of the building (built with the Build Gun), so it has no item. */
    public static final Supplier<DronePortBlock> DRONE_PORT = blockOnly("drone_port", DronePortBlock::new,
        () -> machine().noLootTable());
    public static final Supplier<PortalBlock> MAIN_PORTAL = block("main_portal", properties -> new PortalBlock(true, properties), ModBlocks::machine);
    public static final Supplier<PortalBlock> SATELLITE_PORTAL = block("satellite_portal", properties -> new PortalBlock(false, properties), ModBlocks::machine);
    /**
     * The Blueprint Designers' terminals, each the controller of its Blueprint Designer building (Mk.1 to Mk.3, built with
     * the Build Gun): only ever part of the building, so they have no item. Mk.2 (FICSIT Blueprints Mk.2) is between
     * Mk.1's 9-block cube and Mk.3's 17, as 1.0's 40 m is between 32 and 48.
     */
    public static final Supplier<DesignerBlock> BLUEPRINT_DESIGNER = blockOnly("blueprint_designer",
        properties -> new DesignerBlock(DESIGNER_MK1_REACH, properties), () -> machine().noLootTable());
    public static final Supplier<DesignerBlock> BLUEPRINT_DESIGNER_MK2 = blockOnly("blueprint_designer_mk2",
        properties -> new DesignerBlock(DESIGNER_MK2_REACH, properties), () -> machine().noLootTable());
    public static final Supplier<DesignerBlock> BLUEPRINT_DESIGNER_MK3 = blockOnly("blueprint_designer_mk3",
        properties -> new DesignerBlock(DESIGNER_MK3_REACH, properties), () -> machine().noLootTable());
    public static final Supplier<Block> STEEL_CASING = block("steel_casing", Block::new, () -> BlockBehaviour.Properties.of().strength(3.0F, 6.0F).sound(SoundType.METAL));

    public static final Supplier<MamBlock> MAM = block("mam", MamBlock::new, ModBlocks::machine);

    /** Mercer Spheres, Somersloops and crash site pods: glowing a little, and impossible to break. */
    public static final Map<Collectible, Supplier<CollectibleBlock>> COLLECTIBLES = new EnumMap<>(Collectible.class);

    static {
        for (Collectible type : Collectible.values()) {
            Identifier id = Siftec.id(type.id() + "_block");
            ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, id);
            BlockBehaviour.Properties properties = node().noOcclusion().lightLevel(state -> type.light)
                .sound(type == Collectible.CRASH_SITE ? SoundType.METAL : SoundType.AMETHYST).setId(key);
            CollectibleBlock block = Registry.register(BuiltInRegistries.BLOCK, key, new CollectibleBlock(type, properties));
            COLLECTIBLES.put(type, () -> block);
        }
    }

    /** The Manufacturer's and the Blender's Brass Basin: only ever part of a building, so it has no item. */
    public static final Supplier<BrassBasinBlock> BRASS_BASIN = blockOnly("brass_basin",
        BrassBasinBlock::new, () -> machine().sound(SoundType.NETHERITE_BLOCK));

    public static final Supplier<ClaimMarkerBlock> CLAIM_MARKER = block("claim_marker", ClaimMarkerBlock::new, ModBlocks::machine);
    /**
     * A dead player's things, where they died (death/DeathCrates.java): placed only by the game, so it has no item.
     * Nothing breaks, blows up or pushes it, and it drops nothing; its owner empties it by clicking it.
     */
    public static final Supplier<DeathCrateBlock> DEATH_CRATE = blockOnly("death_crate", DeathCrateBlock::new,
        () -> BlockBehaviour.Properties.of().strength(UNBREAKABLE_HARDNESS, UNBREAKABLE_RESISTANCE).noLootTable()
            .sound(SoundType.WOOD).pushReaction(PushReaction.IMMOVEABLE));

    /** The AWESOME Shop's decorations (sink/DecorBlock.java), by id, from siftec_content.json (tools/content.py, DECOR). */
    public static final Map<String, Block> DECOR = new LinkedHashMap<>();

    static {
        for (JsonElement e : Milestones.raw().getAsJsonArray("decor")) {
            JsonObject o = e.getAsJsonObject();
            String id = o.get("id").getAsString();
            int light = o.get("light").getAsInt();
            JsonArray b = o.getAsJsonArray("shape");
            VoxelShape shape = Block.box(b.get(0).getAsDouble(), b.get(1).getAsDouble(), b.get(2).getAsDouble(),
                b.get(3).getAsDouble(), b.get(4).getAsDouble(), b.get(5).getAsDouble());
            Supplier<DecorBlock> block = block(id, properties -> new DecorBlock(shape, properties),
                () -> BlockBehaviour.Properties.of().strength(1.5F, 6.0F).sound(SoundType.METAL).noOcclusion().lightLevel(state -> light));
            DECOR.put(id, block.get());
        }
    }

    /** The AWESOME Shop's signs (cosmetic/ShopSignBlock.java): a Label Sign and a Display Sign, and a billboard's own sign. */
    public static final Supplier<ShopSignBlock> LABEL_SIGN = block("label_sign",
        properties -> new ShopSignBlock(1, 0.5f, 0, 0, 4, 12, properties), ModBlocks::signProperties);
    public static final Supplier<ShopSignBlock> DISPLAY_SIGN = block("display_sign",
        properties -> new ShopSignBlock(1, 1, 0, 0, 0, 16, properties), ModBlocks::signProperties);
    public static final Supplier<ShopSignBlock> BILLBOARD_SMALL_SIGN = blockOnly("billboard_small_sign",
        properties -> new ShopSignBlock(6, 2, -0.5f, 1.5f, 0, 16, properties), ModBlocks::signProperties);
    public static final Supplier<ShopSignBlock> BILLBOARD_LARGE_SIGN = blockOnly("billboard_large_sign",
        properties -> new ShopSignBlock(14, 6, -0.5f, 3.5f, 0, 16, properties), ModBlocks::signProperties);
    /** The Boom Box (cosmetic/BoomBoxBlock.java): a jukebox that plays the Shop's cassette tapes. */
    public static final Supplier<BoomBoxBlock> BOOM_BOX = block("boom_box", BoomBoxBlock::new,
        () -> BlockBehaviour.Properties.of().strength(1.5F, 6.0F).sound(SoundType.METAL).noOcclusion());

    private static BlockBehaviour.Properties signProperties() {
        return BlockBehaviour.Properties.of().strength(1.0F).sound(SoundType.METAL).noCollision().noOcclusion();
    }

    private static <T extends Block> Supplier<T> hidden(
        String name, Function<BlockBehaviour.Properties, T> factory, Supplier<BlockBehaviour.Properties> properties
    ) {
        Supplier<T> block = block(name, factory, properties);
        TAB_ITEMS.removeLast();
        return block;
    }

    /** A block with no item: it can only be placed by the mod itself. */
    private static <T extends Block> Supplier<T> blockOnly(
        String name, Function<BlockBehaviour.Properties, T> factory, Supplier<BlockBehaviour.Properties> properties
    ) {
        ResourceKey<Block> blockKey = ResourceKey.create(Registries.BLOCK, Siftec.id(name));
        T block = Registry.register(BuiltInRegistries.BLOCK, blockKey, factory.apply(properties.get().setId(blockKey)));
        return () -> block;
    }

    /** The Wormhole Gateway: the item is a {@link PrefabItem}, which places the whole structure. */
    private static Supplier<HubBlock> prefab(String name, Prefabs.Kind kind, Function<BlockBehaviour.Properties, HubBlock> factory) {
        Identifier id = Siftec.id(name);
        ResourceKey<Block> blockKey = ResourceKey.create(Registries.BLOCK, id);
        HubBlock block = Registry.register(BuiltInRegistries.BLOCK, blockKey, factory.apply(machine().setId(blockKey)));
        ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, id);
        BlockItem item = new PrefabItem(kind, block, new Item.Properties().setId(itemKey).useBlockDescriptionPrefix());
        item.registerBlocks(Item.BY_BLOCK, item);
        Registry.register(BuiltInRegistries.ITEM, itemKey, item);
        TAB_ITEMS.add(item);
        return () -> block;
    }

    private static <T extends Block> Supplier<T> block(
        String name, Function<BlockBehaviour.Properties, T> factory, Supplier<BlockBehaviour.Properties> properties
    ) {
        Identifier id = Siftec.id(name);
        ResourceKey<Block> blockKey = ResourceKey.create(Registries.BLOCK, id);
        T block = Registry.register(BuiltInRegistries.BLOCK, blockKey, factory.apply(properties.get().setId(blockKey)));
        ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, id);
        BlockItem item = new BlockItem(block, new Item.Properties().setId(itemKey).useBlockDescriptionPrefix());
        item.registerBlocks(Item.BY_BLOCK, item);
        Registry.register(BuiltInRegistries.ITEM, itemKey, item);
        TAB_ITEMS.add(item);
        return () -> block;
    }

    public static void register() {
    }
}
