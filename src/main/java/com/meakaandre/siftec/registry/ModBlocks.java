package com.meakaandre.siftec.registry;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.block.ExtractorBlock;
import com.meakaandre.siftec.block.MinerBlock;
import com.meakaandre.siftec.claim.ClaimMarkerBlock;
import com.meakaandre.siftec.collect.Collectible;
import com.meakaandre.siftec.collect.CollectibleBlock;
import com.meakaandre.siftec.depot.DepotBlock;
import com.meakaandre.siftec.geyser.GeyserEngineBlock;
import com.meakaandre.siftec.hub.HubBlock;
import com.meakaandre.siftec.machine.ProcessorBlock;
import com.meakaandre.siftec.sink.SinkBlock;
import com.meakaandre.siftec.mam.MamBlock;
import com.meakaandre.siftec.power.PoleBlock;
import com.meakaandre.siftec.power.StorageBlock;
import com.meakaandre.siftec.workshop.WorkshopBlock;
import com.meakaandre.siftec.block.MinerTier;
import com.meakaandre.siftec.block.NodeBlock;
import com.meakaandre.siftec.block.NodeRockBlock;
import com.meakaandre.siftec.block.PortableMinerBlock;
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

    /** Node blocks cannot be broken, pushed or blown up. */
    private static BlockBehaviour.Properties node() {
        return BlockBehaviour.Properties.of().strength(-1.0F, 3600000.0F).noLootTable()
            .sound(SoundType.DEEPSLATE).pushReaction(PushReaction.IMMOVEABLE);
    }

    /** A node's own blocks: unbreakable like the rest, and drawn as a mound that does not fill its block. */
    private static BlockBehaviour.Properties nodeCore() {
        return node().noOcclusion();
    }

    private static BlockBehaviour.Properties machine() {
        return BlockBehaviour.Properties.of().strength(2.0F, 6.0F).sound(SoundType.METAL).noOcclusion();
    }

    public static final Supplier<NodeRockBlock> NODE_ROCK = block("node_rock", NodeRockBlock::new, ModBlocks::node);
    public static final Map<NodeType, Supplier<NodeBlock>> NODES = new EnumMap<>(NodeType.class);

    static {
        for (NodeType type : NodeType.values()) {
            NODES.put(type, block(type.id() + "_node", properties -> new NodeBlock(type, properties), ModBlocks::nodeCore));
        }
    }

    public static final Supplier<PortableMinerBlock> PORTABLE_MINER = block("portable_miner", PortableMinerBlock::new, ModBlocks::machine);
    /** The middle of an oil pool: the one block a Pumpjack can stand on. */
    public static final Supplier<NodeBlock> OIL_WELL = block("oil_well", properties -> new NodeBlock(NodeType.OIL, properties), ModBlocks::nodeCore);
    /** The flat pad a node stands on, and the fill under it: unbreakable, immovable, not in the creative tab (ops can /give it). */
    public static final Supplier<com.meakaandre.siftec.block.NodePadBlock> NODE_PAD = hidden("node_pad", com.meakaandre.siftec.block.NodePadBlock::new, ModBlocks::node);
    public static final Supplier<com.meakaandre.siftec.block.NodePadBlock> NODE_PAD_FILL = hidden("node_pad_fill", com.meakaandre.siftec.block.NodePadBlock::new, ModBlocks::node);
    /**
     * Each node type's pad, also used for the fill under it: a plain block of that node's natural stone (the stone is
     * chosen in tools/gen_assets.py, STONE), unbreakable and immovable like the old pad, with no item and no loot.
     */
    public static final Map<NodeType, Supplier<com.meakaandre.siftec.block.NodePadBlock>> NODE_PADS = new EnumMap<>(NodeType.class);

    static {
        for (NodeType type : NodeType.values()) {
            NODE_PADS.put(type, blockOnly(type.id() + "_node_pad", com.meakaandre.siftec.block.NodePadBlock::new, ModBlocks::node));
        }
    }

    public static final Supplier<MinerBlock> MINER_MK1 = block("miner_mk1", properties -> new MinerBlock(MinerTier.MK1, properties), ModBlocks::machine);
    public static final Supplier<MinerBlock> MINER_MK2 = block("miner_mk2", properties -> new MinerBlock(MinerTier.MK2, properties), ModBlocks::machine);
    public static final Supplier<MinerBlock> MINER_MK3 = block("miner_mk3", properties -> new MinerBlock(MinerTier.MK3, properties), ModBlocks::machine);
    /** The upper block of a powered miner (placed with it, never on its own). */
    public static final Supplier<com.meakaandre.siftec.block.MinerTopBlock> MINER_TOP = hidden("miner_top", com.meakaandre.siftec.block.MinerTopBlock::new, ModBlocks::machine);
    public static final Supplier<ExtractorBlock> RESOURCE_WELL_EXTRACTOR = block("resource_well_extractor", ExtractorBlock::new, ModBlocks::machine);

    /** The HUB Terminal and the Wormhole Gateway's terminal; their items place the whole structure (see prefab.Prefabs). */
    public static final Supplier<HubBlock> HUB = prefab("hub", com.meakaandre.siftec.prefab.Prefabs.Kind.HUB, properties -> new HubBlock(false, properties));
    public static final Supplier<HubBlock> WORMHOLE_GATEWAY = prefab("wormhole_gateway", com.meakaandre.siftec.prefab.Prefabs.Kind.GATEWAY, properties -> new HubBlock(true, properties));

    /**
     * The shell blocks of the HUB and Wormhole Gateway structures, by style: Create and vanilla textures (see
     * tools/gen_assets.py, PREFAB). Unbreakable, immovable, no item and no loot, like node pads.
     */
    public static final Map<String, Supplier<com.meakaandre.siftec.prefab.PrefabBlock>> PREFAB = new java.util.LinkedHashMap<>();

    static {
        // style, the vanilla block whose sound, light and see-through-ness it copies, light level
        Object[][] styles = {{"white", net.minecraft.world.level.block.Blocks.WHITE_CONCRETE, 0}, {"orange", net.minecraft.world.level.block.Blocks.ORANGE_CONCRETE, 0},
            {"metal", net.minecraft.world.level.block.Blocks.IRON_BLOCK, 0}, {"floor", net.minecraft.world.level.block.Blocks.LIGHT_GRAY_CONCRETE, 0},
            {"glass", net.minecraft.world.level.block.Blocks.GLASS, 0}, {"lamp", net.minecraft.world.level.block.Blocks.REDSTONE_LAMP, 15},
            {"step", net.minecraft.world.level.block.Blocks.LIGHT_GRAY_CONCRETE, 0}, {"locker", net.minecraft.world.level.block.Blocks.IRON_BLOCK, 0},
            {"railway", net.minecraft.world.level.block.Blocks.IRON_BLOCK, 0}, {"coil", net.minecraft.world.level.block.Blocks.IRON_BLOCK, 0},
            {"coil_lit", net.minecraft.world.level.block.Blocks.AMETHYST_BLOCK, 15}, {"rift", net.minecraft.world.level.block.Blocks.CRYING_OBSIDIAN, 15},
            {"foundation", net.minecraft.world.level.block.Blocks.GRAY_CONCRETE, 0}};
        for (Object[] style : styles) {
            String name = (String) style[0];
            Block like = (Block) style[1];
            int light = (Integer) style[2];
            PREFAB.put(name, blockOnly("prefab_" + name, properties -> new com.meakaandre.siftec.prefab.PrefabBlock(name.equals("step"), properties),
                () -> prefabProperties(like, light, name.equals("step"))));
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
        () -> prefabProperties(net.minecraft.world.level.block.Blocks.ORANGE_BED, 0, true));
    public static final Supplier<com.meakaandre.siftec.prefab.CraftBenchBlock> CRAFT_BENCH = blockOnly("craft_bench", com.meakaandre.siftec.prefab.CraftBenchBlock::new,
        () -> prefabProperties(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE, 0));
    public static final Supplier<com.meakaandre.siftec.prefab.StorageBoxBlock> PERSONAL_STORAGE = blockOnly("personal_storage", com.meakaandre.siftec.prefab.StorageBoxBlock::new,
        () -> prefabProperties(net.minecraft.world.level.block.Blocks.BARREL, 0));
    public static final Supplier<com.meakaandre.siftec.prefab.HubBurnerBlock> HUB_BURNER = blockOnly("hub_burner", com.meakaandre.siftec.prefab.HubBurnerBlock::new,
        () -> BlockBehaviour.Properties.of().strength(-1.0F, 3600000.0F).noLootTable().sound(SoundType.METAL).noOcclusion().pushReaction(PushReaction.IMMOVEABLE));

    public static final Supplier<WorkshopBlock> EQUIPMENT_WORKSHOP = block("equipment_workshop", WorkshopBlock::new, ModBlocks::machine);

    public static final Supplier<DepotBlock> DIMENSIONAL_DEPOT = block("dimensional_depot", DepotBlock::new, ModBlocks::machine);
    public static final Supplier<PoleBlock> POWER_POLE = block("power_pole", properties -> new PoleBlock(24, properties), ModBlocks::machine);
    public static final Supplier<PoleBlock> POWER_TOWER = block("power_tower", properties -> new PoleBlock(256, properties), ModBlocks::machine);
    public static final Supplier<com.meakaandre.siftec.governor.GovernorBlock> SPEED_GOVERNOR = block("speed_governor", com.meakaandre.siftec.governor.GovernorBlock::new, ModBlocks::machine);
    public static final Supplier<com.meakaandre.siftec.engine.EngineBlock> FURNACE_ENGINE = block("furnace_engine", properties -> new com.meakaandre.siftec.engine.EngineBlock(false, properties), ModBlocks::machine);
    public static final Supplier<com.meakaandre.siftec.engine.EngineBlock> HUB_ENGINE = block("hub_engine", properties -> new com.meakaandre.siftec.engine.EngineBlock(true, properties), ModBlocks::machine);
    public static final Supplier<com.meakaandre.siftec.equip.LandingPadBlock> LANDING_PAD = block("landing_pad", com.meakaandre.siftec.equip.LandingPadBlock::new, ModBlocks::machine);
    public static final Supplier<com.meakaandre.siftec.equip.RadarBlock> RADAR_TOWER = block("radar_tower", com.meakaandre.siftec.equip.RadarBlock::new, ModBlocks::machine);
    public static final Supplier<com.meakaandre.siftec.drone.DronePortBlock> DRONE_PORT = block("drone_port", com.meakaandre.siftec.drone.DronePortBlock::new, ModBlocks::machine);
    public static final Supplier<com.meakaandre.siftec.portal.PortalBlock> MAIN_PORTAL = block("main_portal", properties -> new com.meakaandre.siftec.portal.PortalBlock(true, properties), ModBlocks::machine);
    public static final Supplier<com.meakaandre.siftec.portal.PortalBlock> SATELLITE_PORTAL = block("satellite_portal", properties -> new com.meakaandre.siftec.portal.PortalBlock(false, properties), ModBlocks::machine);
    public static final Supplier<com.meakaandre.siftec.blueprint.DesignerBlock> BLUEPRINT_DESIGNER = block("blueprint_designer", properties -> new com.meakaandre.siftec.blueprint.DesignerBlock(4, properties), ModBlocks::machine);
    public static final Supplier<com.meakaandre.siftec.blueprint.DesignerBlock> BLUEPRINT_DESIGNER_MK3 = block("blueprint_designer_mk3", properties -> new com.meakaandre.siftec.blueprint.DesignerBlock(8, properties), ModBlocks::machine);
    public static final Supplier<Block> STEEL_CASING = block("steel_casing", Block::new, () -> BlockBehaviour.Properties.of().strength(3.0F, 6.0F).sound(SoundType.METAL));
    public static final Supplier<StorageBlock> POWER_STORAGE = block("power_storage", StorageBlock::new, ModBlocks::machine);

    public static final Supplier<MamBlock> MAM = block("mam", MamBlock::new, ModBlocks::machine);

    /** Slugs and artefacts: small, glowing, and impossible to break. */
    public static final Map<Collectible, Supplier<CollectibleBlock>> COLLECTIBLES = new EnumMap<>(Collectible.class);

    static {
        for (Collectible type : Collectible.values()) {
            Identifier id = Siftec.id(type.id() + "_block");
            ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, id);
            BlockBehaviour.Properties properties = node().noOcclusion().lightLevel(state -> type.light).sound(SoundType.SLIME_BLOCK).setId(key);
            // the slugs glow: drawn at full brightness however dark it is round them
            if (type.slug()) properties = properties.emissiveRendering(state -> true);
            CollectibleBlock block = Registry.register(BuiltInRegistries.BLOCK, key, new CollectibleBlock(type, properties));
            COLLECTIBLES.put(type, () -> block);
        }
    }

    public static final Supplier<GeyserEngineBlock> GEYSER_ENGINE = block("geyser_engine", GeyserEngineBlock::new, ModBlocks::machine);
    public static final Supplier<ProcessorBlock> CONVERTER = block("converter", properties -> new ProcessorBlock("converter", properties), ModBlocks::machine);
    public static final Supplier<ProcessorBlock> PARTICLE_ACCELERATOR = block("particle_accelerator", properties -> new ProcessorBlock("particle_accelerator", properties), ModBlocks::machine);
    public static final Supplier<SinkBlock> AWESOME_SINK = block("awesome_sink", properties -> new SinkBlock(false, properties), ModBlocks::machine);
    public static final Supplier<SinkBlock> AWESOME_SHOP = block("awesome_shop", properties -> new SinkBlock(true, properties), ModBlocks::machine);
    public static final Supplier<ClaimMarkerBlock> CLAIM_MARKER = block("claim_marker", ClaimMarkerBlock::new, ModBlocks::machine);

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

    /** The HUB and Wormhole Gateway: the item is a {@link com.meakaandre.siftec.prefab.PrefabItem}, which places the whole structure. */
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
