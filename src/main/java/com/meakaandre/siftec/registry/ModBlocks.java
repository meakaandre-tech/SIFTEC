package com.meakaandre.siftec.registry;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.block.ExtractorBlock;
import com.meakaandre.siftec.claim.ClaimMarkerBlock;
import com.meakaandre.siftec.collect.Collectible;
import com.meakaandre.siftec.collect.CollectibleBlock;
import com.meakaandre.siftec.depot.DepotBlock;
import com.meakaandre.siftec.hub.HubBlock;
import com.meakaandre.siftec.machine.ProcessorBlock;
import com.meakaandre.siftec.mam.MamBlock;
import com.meakaandre.siftec.block.NodeBlock;
import com.meakaandre.siftec.node.NodeLook;
import com.meakaandre.siftec.node.NodeType;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.PushReaction;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

public class ModBlocks {
    /** Items shown in the creative tab, in registration order. */
    public static final List<Item> TAB_ITEMS = new ArrayList<>();

    /** Patches, depleted nodes and fluid nodes cannot be broken, pushed or blown up. */
    private static BlockBehaviour.Properties node() {
        return BlockBehaviour.Properties.of().strength(-1.0F, 3600000.0F).noLootTable()
            .sound(SoundType.DEEPSLATE).pushReaction(PushReaction.IMMOVEABLE);
    }

    /** A node that can be mined: like an ore block to dig, but never pushed or blown up (it regrows instead of going). */
    private static BlockBehaviour.Properties minable() {
        return BlockBehaviour.Properties.of().strength(3.0F, 3600000.0F).requiresCorrectToolForDrops()
            .sound(SoundType.STONE).pushReaction(PushReaction.IMMOVEABLE);
    }

    private static BlockBehaviour.Properties machine() {
        return BlockBehaviour.Properties.of().strength(2.0F, 6.0F).sound(SoundType.METAL).noOcclusion();
    }

    /**
     * Each patch stone's patch: flat unbreakable ground of that stone under a node (see node.NodeLook). Immovable, no
     * item and no loot; Create's drills skip it (in create:non_movable, hardness -1).
     */
    public static final Map<String, Supplier<com.meakaandre.siftec.block.NodePadBlock>> PATCHES = new java.util.LinkedHashMap<>();
    /** The node of each look ({@code <look>_node}); oil's is the Oil Well. */
    public static final Map<String, Supplier<NodeBlock>> NODES = new java.util.LinkedHashMap<>();
    /** The mined node of each look that can be mined ({@code <look>_node_depleted}), growing back. */
    public static final Map<String, Supplier<com.meakaandre.siftec.block.DepletedNodeBlock>> DEPLETED = new java.util.LinkedHashMap<>();

    static {
        for (String patch : NodeLook.patches()) {
            PATCHES.put(patch, blockOnly(patch + "_patch", com.meakaandre.siftec.block.NodePadBlock::new, ModBlocks::node));
        }
        for (NodeLook look : NodeLook.all()) {
            if (look.type() == NodeType.OIL) continue;
            Supplier<BlockBehaviour.Properties> nodeProperties = look.fluid() ? ModBlocks::node : ModBlocks::minable;
            NODES.put(look.id(), hidden(look.id() + "_node", p -> new NodeBlock(look, p), nodeProperties));
            if (!look.fluid()) {
                DEPLETED.put(look.id(), blockOnly(look.id() + "_node_depleted", properties -> new com.meakaandre.siftec.block.DepletedNodeBlock(look, properties), () -> node().noOcclusion()));
            }
        }
    }

    /** The middle of an oil node: the one block a Pumpjack's pipe counts as an oil deposit. */
    public static final Supplier<NodeBlock> OIL_WELL = hidden("oil_well", properties -> new NodeBlock(NodeLook.byId("oil"), properties), ModBlocks::node);

    static {
        NODES.put("oil", OIL_WELL);
    }

    /** Uranium in a uranium node's clump: mined like an ore, it gives Uranium. */
    public static final Supplier<Block> URANIUM_ORE = blockOnly("uranium_ore", Block::new,
        () -> BlockBehaviour.Properties.of().strength(3.0F, 3.0F).requiresCorrectToolForDrops().sound(SoundType.STONE));

    public static final Supplier<ExtractorBlock> RESOURCE_WELL_EXTRACTOR = block("resource_well_extractor", ExtractorBlock::new, ModBlocks::machine);

    /** The HUB Terminal and the Wormhole Gateway's terminal; their items place the whole structure (see prefab.Prefabs). */
    /** The HUB's terminal. The HUB has no item: every player builds it with the Build Gun (it is listed in content BUILDINGS). */
    public static final Supplier<HubBlock> HUB = blockOnly("hub", properties -> new HubBlock(false, properties), ModBlocks::machine);
    public static final Supplier<HubBlock> WORMHOLE_GATEWAY = prefab("wormhole_gateway", com.meakaandre.siftec.prefab.Prefabs.Kind.GATEWAY, properties -> new HubBlock(true, properties));

    /**
     * The shell blocks of the HUB and Wormhole Gateway structures, by style: Create and vanilla textures (see
     * tools/gen_assets.py, PREFAB). Unbreakable, immovable, no item and no loot, like node pads.
     */
    public static final Map<String, Supplier<com.meakaandre.siftec.prefab.PrefabBlock>> PREFAB = new java.util.LinkedHashMap<>();

    static {
        // style, the vanilla block whose sound, light and see-through-ness it copies, light level
        Object[][] styles = {{"white", net.minecraft.world.level.block.Blocks.CONCRETE.white(), 0}, {"orange", net.minecraft.world.level.block.Blocks.CONCRETE.orange(), 0},
            {"metal", net.minecraft.world.level.block.Blocks.IRON_BLOCK, 0}, {"floor", net.minecraft.world.level.block.Blocks.CONCRETE.lightGray(), 0},
            {"glass", net.minecraft.world.level.block.Blocks.GLASS, 0}, {"lamp", net.minecraft.world.level.block.Blocks.REDSTONE_LAMP, 15},
            {"step", net.minecraft.world.level.block.Blocks.CONCRETE.lightGray(), 0}, {"locker", net.minecraft.world.level.block.Blocks.IRON_BLOCK, 0},
            {"railway", net.minecraft.world.level.block.Blocks.IRON_BLOCK, 0}, {"coil", net.minecraft.world.level.block.Blocks.IRON_BLOCK, 0},
            {"coil_lit", net.minecraft.world.level.block.Blocks.AMETHYST_BLOCK, 15}, {"rift", net.minecraft.world.level.block.Blocks.CRYING_OBSIDIAN, 15},
            {"foundation", net.minecraft.world.level.block.Blocks.CONCRETE.gray(), 0},
            // Polartt's yellow and black hazard stripes, on the HUB's platform edges
            {"hazard_yellow", net.minecraft.world.level.block.Blocks.CONCRETE.yellow(), 0}, {"hazard_black", net.minecraft.world.level.block.Blocks.CONCRETE.black(), 0},
            // the Wormhole Gateway's energy conduits, and its portal: no collision, see-through, glowing (it swirls like a Nether portal)
            {"conduit", net.minecraft.world.level.block.Blocks.IRON_BLOCK, 4}, {"portal", net.minecraft.world.level.block.Blocks.NETHER_PORTAL, 15}};
        for (Object[] style : styles) {
            String name = (String) style[0];
            Block like = (Block) style[1];
            int light = (Integer) style[2];
            PREFAB.put(name, blockOnly("prefab_" + name, properties -> new com.meakaandre.siftec.prefab.PrefabBlock(name.equals("step"), properties),
                () -> prefabProperties(like, light, name.equals("step") || name.equals("portal"))));
        }
    }

    private static BlockBehaviour.Properties prefabProperties(Block like, int light) {
        return prefabProperties(like, light, false);
    }

    private static BlockBehaviour.Properties prefabProperties(Block like, int light, boolean open) {
        BlockBehaviour.Properties p = BlockBehaviour.Properties.ofFullCopy(like).strength(-1.0F, 3600000.0F).noLootTable()
            .pushReaction(PushReaction.IMMOVEABLE).lightLevel(state -> light);
        return open ? p.noOcclusion() : p;
    }

    public static final Supplier<com.meakaandre.siftec.prefab.HubBedBlock> HUB_BED = blockOnly("hub_bed", com.meakaandre.siftec.prefab.HubBedBlock::new,
        () -> prefabProperties(net.minecraft.world.level.block.Blocks.BED.orange(), 0, true));
    public static final Supplier<com.meakaandre.siftec.prefab.StorageBoxBlock> PERSONAL_STORAGE = blockOnly("personal_storage", com.meakaandre.siftec.prefab.StorageBoxBlock::new,
        () -> prefabProperties(net.minecraft.world.level.block.Blocks.BARREL, 0));
    public static final Supplier<com.meakaandre.siftec.prefab.HubBurnerBlock> HUB_BURNER = blockOnly("hub_burner", com.meakaandre.siftec.prefab.HubBurnerBlock::new,
        () -> BlockBehaviour.Properties.of().strength(-1.0F, 3600000.0F).noLootTable().sound(SoundType.METAL).noOcclusion().pushReaction(PushReaction.IMMOVEABLE));

    /**
     * The Build Gun's buildings (see building.Buildings): each one's controller and its ports. Like the HUB's shell,
     * unbreakable and immovable; their items exist (not in the tab) only so buildings can be built and saved with a
     * Structure Block.
     */
    public static final Supplier<com.meakaandre.siftec.building.ControllerBlock> BUILDING_CONTROLLER = hidden("building_controller",
        com.meakaandre.siftec.building.ControllerBlock::new, () -> prefabProperties(net.minecraft.world.level.block.Blocks.IRON_BLOCK, 0));
    public static final Supplier<com.meakaandre.siftec.building.PortBlock> BUILDING_PORT = hidden("building_port",
        com.meakaandre.siftec.building.PortBlock::new, () -> prefabProperties(net.minecraft.world.level.block.Blocks.IRON_BLOCK, 0));
    /** The turbine inside a generator building, turning its generator coils while it runs (see building.TurbineBlock). */
    public static final Supplier<com.meakaandre.siftec.building.TurbineBlock> TURBINE = hidden("turbine",
        com.meakaandre.siftec.building.TurbineBlock::new, () -> prefabProperties(net.minecraft.world.level.block.Blocks.IRON_BLOCK, 0).noOcclusion());


    public static final Supplier<DepotBlock> DIMENSIONAL_DEPOT = block("dimensional_depot", DepotBlock::new, ModBlocks::machine);
    public static final Supplier<com.meakaandre.siftec.governor.GovernorBlock> SPEED_GOVERNOR = block("speed_governor", com.meakaandre.siftec.governor.GovernorBlock::new, ModBlocks::machine);
    public static final Supplier<com.meakaandre.siftec.equip.LandingPadBlock> LANDING_PAD = block("landing_pad", com.meakaandre.siftec.equip.LandingPadBlock::new, ModBlocks::machine);
    public static final Supplier<com.meakaandre.siftec.drone.DronePortBlock> DRONE_PORT = block("drone_port", com.meakaandre.siftec.drone.DronePortBlock::new, ModBlocks::machine);
    public static final Supplier<com.meakaandre.siftec.portal.PortalBlock> MAIN_PORTAL = block("main_portal", properties -> new com.meakaandre.siftec.portal.PortalBlock(true, properties), ModBlocks::machine);
    public static final Supplier<com.meakaandre.siftec.portal.PortalBlock> SATELLITE_PORTAL = block("satellite_portal", properties -> new com.meakaandre.siftec.portal.PortalBlock(false, properties), ModBlocks::machine);
    public static final Supplier<com.meakaandre.siftec.blueprint.DesignerBlock> BLUEPRINT_DESIGNER = block("blueprint_designer", properties -> new com.meakaandre.siftec.blueprint.DesignerBlock(4, properties), ModBlocks::machine);
    public static final Supplier<com.meakaandre.siftec.blueprint.DesignerBlock> BLUEPRINT_DESIGNER_MK3 = block("blueprint_designer_mk3", properties -> new com.meakaandre.siftec.blueprint.DesignerBlock(8, properties), ModBlocks::machine);
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
    public static final Supplier<com.meakaandre.siftec.building.BrassBasinBlock> BRASS_BASIN = blockOnly("brass_basin",
        com.meakaandre.siftec.building.BrassBasinBlock::new, () -> machine().sound(SoundType.NETHERITE_BLOCK));

    public static final Supplier<ProcessorBlock> CONVERTER = block("converter", properties -> new ProcessorBlock("converter", properties), ModBlocks::machine);
    public static final Supplier<ProcessorBlock> PARTICLE_ACCELERATOR = block("particle_accelerator", properties -> new ProcessorBlock("particle_accelerator", properties), ModBlocks::machine);
    public static final Supplier<ClaimMarkerBlock> CLAIM_MARKER = block("claim_marker", ClaimMarkerBlock::new, ModBlocks::machine);

    /** The AWESOME Shop's decorations (sink/DecorBlock.java), by id, from siftec_content.json (tools/content.py, DECOR). */
    public static final Map<String, Block> DECOR = new java.util.LinkedHashMap<>();

    static {
        for (com.google.gson.JsonElement e : com.meakaandre.siftec.hub.Milestones.raw().getAsJsonArray("decor")) {
            com.google.gson.JsonObject o = e.getAsJsonObject();
            String id = o.get("id").getAsString();
            int light = o.get("light").getAsInt();
            com.google.gson.JsonArray b = o.getAsJsonArray("shape");
            net.minecraft.world.phys.shapes.VoxelShape shape = Block.box(b.get(0).getAsDouble(), b.get(1).getAsDouble(), b.get(2).getAsDouble(),
                b.get(3).getAsDouble(), b.get(4).getAsDouble(), b.get(5).getAsDouble());
            Supplier<com.meakaandre.siftec.sink.DecorBlock> block = block(id, properties -> new com.meakaandre.siftec.sink.DecorBlock(shape, properties),
                () -> BlockBehaviour.Properties.of().strength(1.5F, 6.0F).sound(SoundType.METAL).noOcclusion().lightLevel(state -> light));
            DECOR.put(id, block.get());
        }
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

    /** The Wormhole Gateway: the item is a {@link com.meakaandre.siftec.prefab.PrefabItem}, which places the whole structure. */
    private static Supplier<HubBlock> prefab(String name, com.meakaandre.siftec.prefab.Prefabs.Kind kind, Function<BlockBehaviour.Properties, HubBlock> factory) {
        Identifier id = Siftec.id(name);
        ResourceKey<Block> blockKey = ResourceKey.create(Registries.BLOCK, id);
        HubBlock block = Registry.register(BuiltInRegistries.BLOCK, blockKey, factory.apply(machine().setId(blockKey)));
        ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, id);
        BlockItem item = new com.meakaandre.siftec.prefab.PrefabItem(kind, block, new Item.Properties().setId(itemKey).useBlockDescriptionPrefix());
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
