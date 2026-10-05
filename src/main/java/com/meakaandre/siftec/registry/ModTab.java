package com.meakaandre.siftec.registry;

import com.meakaandre.siftec.Siftec;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public class ModTab {
    public static void register() {
        Registry.register(
            BuiltInRegistries.CREATIVE_MODE_TAB,
            Siftec.id("siftec"),
            FabricCreativeModeTab.builder()
                .title(Component.translatable("itemGroup.siftec"))
                .icon(() -> new ItemStack(ModItems.NODE_SCANNER.get()))
                .displayItems((parameters, output) -> {
                    for (Item item : ModBlocks.TAB_ITEMS) output.accept(item);
                })
                .build()
        );
    }
}
