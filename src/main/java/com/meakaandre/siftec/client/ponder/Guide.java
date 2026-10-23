package com.meakaandre.siftec.client.ponder;

import com.meakaandre.siftec.building.BuildingDef;
import com.meakaandre.siftec.building.Buildings;
import com.meakaandre.siftec.client.BuildGunClient;
import com.meakaandre.siftec.registry.ModItems;
import com.zurrtum.create.client.catnip.gui.ScreenOpener;
import com.zurrtum.create.client.ponder.foundation.PonderIndex;
import com.zurrtum.create.client.ponder.foundation.ui.PonderUI;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;

/**
 * The guide on the client: SIFTEC's Ponder plugin, opening a building's scene (the Build Gun's H
 * key), and the guide items' and the Build Gun's tooltip lines.
 */
public final class Guide {
    private Guide() {
    }

    public static void register() {
        PonderIndex.addPlugin(new SiftecPonderPlugin());
        Map<Item, BuildingDef> byItem = new HashMap<>();
        ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
            if (byItem.isEmpty()) {
                for (BuildingDef def : Buildings.all()) {
                    Item item = ModItems.BUILDINGS.get(def.id());
                    if (item != null) byItem.put(item, def);
                }
            }
            BuildingDef def = byItem.get(stack.getItem());
            if (def != null) {
                lines.add(Component.translatable("siftec.guide.tooltip", Component.translatable(def.category().key())).withStyle(ChatFormatting.GRAY));
            } else if (stack.is(ModItems.BUILD_GUN.get())) {
                lines.add(Component.translatable("siftec.guide.ponder_hint", BuildGunClient.PONDER.getTranslatedKeyMessage()).withStyle(ChatFormatting.GRAY));
            }
        });
    }

    /** Opens a building's Ponder scene. */
    public static void open(BuildingDef def) {
        Item item = ModItems.BUILDINGS.get(def.id());
        if (item == null || !PonderIndex.getSceneAccess().doScenesExistForId(BuiltInRegistries.ITEM.getKey(item))) return;
        ScreenOpener.transitionTo(PonderUI.of(new ItemStack(item)));
    }
}
