package com.meakaandre.siftec.fluid;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.hub.Milestones;
import com.meakaandre.siftec.registry.ModBlocks;
import com.zurrtum.create.AllFluidItemInventory;
import com.zurrtum.create.infrastructure.fluids.BucketFluidInventory;
import com.zurrtum.create.infrastructure.fluids.FluidBlock;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;

import java.util.LinkedHashMap;
import java.util.Map;

/** The pack's fluids (Heavy Oil Residue, Sulfuric Acid, Nitrogen and so on), listed in siftec_content.json. */
public final class ModFluids {
    public static final Map<String, FluidEntry> ALL = new LinkedHashMap<>();

    private ModFluids() {
    }

    public static void register() {
        for (Milestones.FluidDef def : Milestones.fluids()) {
            FluidEntry entry = new FluidEntry(def.id(), def.color());
            Identifier id = Siftec.id(def.id());
            Registry.register(BuiltInRegistries.FLUID, id, entry.still);
            Registry.register(BuiltInRegistries.FLUID, Siftec.id("flowing_" + def.id()), entry.flowing);

            ResourceKey<Block> blockKey = ResourceKey.create(Registries.BLOCK, id);
            entry.block = Registry.register(BuiltInRegistries.BLOCK, blockKey,
                new FluidBlock(entry.still, BlockBehaviour.Properties.ofFullCopy(Blocks.WATER).noLootTable().setId(blockKey)));

            ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, Siftec.id(def.id() + "_bucket"));
            entry.bucket = Registry.register(BuiltInRegistries.ITEM, itemKey,
                new BucketItem(entry.still, new Item.Properties().craftRemainder(Items.BUCKET).stacksTo(1).setId(itemKey)));
            AllFluidItemInventory.ALL.put(entry.bucket, new AllFluidItemInventory.Entry(BucketFluidInventory::new));
            ModBlocks.TAB_ITEMS.add(entry.bucket);
            ALL.put(def.id(), entry);
        }
    }
}
