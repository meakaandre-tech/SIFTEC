package com.meakaandre.siftec.registry;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.block.ExtractorBlock;
import com.meakaandre.siftec.block.MinerBlock;
import com.meakaandre.siftec.depot.DepotBlock;
import com.meakaandre.siftec.hub.HubBlock;
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

    private static BlockBehaviour.Properties machine() {
        return BlockBehaviour.Properties.of().strength(2.0F, 6.0F).sound(SoundType.METAL).noOcclusion();
    }

    public static final Supplier<NodeRockBlock> NODE_ROCK = block("node_rock", NodeRockBlock::new, ModBlocks::node);
    public static final Map<NodeType, Supplier<NodeBlock>> NODES = new EnumMap<>(NodeType.class);

    static {
        for (NodeType type : NodeType.values()) {
            NODES.put(type, block(type.id() + "_node", properties -> new NodeBlock(type, properties), ModBlocks::node));
        }
    }

    public static final Supplier<PortableMinerBlock> PORTABLE_MINER = block("portable_miner", PortableMinerBlock::new, ModBlocks::machine);
    /** The middle of an oil pool: the one block a Pumpjack can stand on. */
    public static final Supplier<NodeBlock> OIL_WELL = block("oil_well", properties -> new NodeBlock(NodeType.OIL, properties), ModBlocks::node);
    public static final Supplier<MinerBlock> MINER_MK1 = block("miner_mk1", properties -> new MinerBlock(MinerTier.MK1, properties), ModBlocks::machine);
    public static final Supplier<MinerBlock> MINER_MK2 = block("miner_mk2", properties -> new MinerBlock(MinerTier.MK2, properties), ModBlocks::machine);
    public static final Supplier<MinerBlock> MINER_MK3 = block("miner_mk3", properties -> new MinerBlock(MinerTier.MK3, properties), ModBlocks::machine);
    public static final Supplier<ExtractorBlock> RESOURCE_WELL_EXTRACTOR = block("resource_well_extractor", ExtractorBlock::new, ModBlocks::machine);

    public static final Supplier<HubBlock> HUB = block("hub", properties -> new HubBlock(false, properties), ModBlocks::machine);
    public static final Supplier<HubBlock> WORMHOLE_GATEWAY = block("wormhole_gateway", properties -> new HubBlock(true, properties), ModBlocks::machine);

    public static final Supplier<WorkshopBlock> EQUIPMENT_WORKSHOP = block("equipment_workshop", WorkshopBlock::new, ModBlocks::machine);

    public static final Supplier<DepotBlock> DIMENSIONAL_DEPOT = block("dimensional_depot", DepotBlock::new, ModBlocks::machine);
    public static final Supplier<PoleBlock> POWER_POLE = block("power_pole", properties -> new PoleBlock(24, properties), ModBlocks::machine);
    public static final Supplier<PoleBlock> POWER_TOWER = block("power_tower", properties -> new PoleBlock(64, properties), ModBlocks::machine);
    public static final Supplier<StorageBlock> POWER_STORAGE = block("power_storage", StorageBlock::new, ModBlocks::machine);

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
