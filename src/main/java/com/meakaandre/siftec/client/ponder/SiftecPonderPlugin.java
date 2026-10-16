package com.meakaandre.siftec.client.ponder;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.building.BuildingDef;
import com.meakaandre.siftec.building.Buildings;
import com.meakaandre.siftec.building.Category;
import com.meakaandre.siftec.registry.ModItems;
import com.zurrtum.create.client.ponder.api.registration.PonderPlugin;
import com.zurrtum.create.client.ponder.api.registration.PonderSceneRegistrationHelper;
import com.zurrtum.create.client.ponder.api.registration.PonderTagRegistrationHelper;
import com.zurrtum.create.client.ponder.api.registration.SharedTextRegistrationHelper;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * SIFTEC's part of Ponder (work package 13): a scene for every building and vehicle ({@link BuildingScenes}), a
 * Ponder tag for each Build Gun tab that has any, and the lines the scenes share. Ponder reads them when the game
 * starts and on every resource reload.
 */
public final class SiftecPonderPlugin implements PonderPlugin {
    /** The shared lines' keys (siftec.ponder.shared.<key> in the lang file, written from tools/guide.py). */
    static final List<String> SHARED = List.of("item_in", "item_out", "fluid_in", "fluid_out", "power_use", "power_make", "power_wire", "fuse",
        "node", "panel", "panel_filter", "cost", "unlock", "dismantle", "vehicle_cost");

    @Override
    public String getModId() {
        return Siftec.MOD_ID;
    }

    /** The Ponder tag of a Build Gun tab: siftec:production, siftec:power and so on. */
    static Identifier tag(Category category) {
        return Siftec.id(category.name().toLowerCase(Locale.ROOT));
    }

    private static Set<Category> used() {
        Set<Category> out = EnumSet.noneOf(Category.class);
        for (BuildingDef def : Buildings.all()) if (!def.dev() && ModItems.BUILDINGS.containsKey(def.id())) out.add(def.category());
        return out;
    }

    @Override
    public void registerScenes(PonderSceneRegistrationHelper<Identifier> helper) {
        BuildingScenes.register(helper);
    }

    @Override
    public void registerTags(PonderTagRegistrationHelper<Identifier> helper) {
        for (Category category : used()) {
            String key = "siftec.ponder.tag." + category.name().toLowerCase(Locale.ROOT);
            helper.registerTag(tag(category)).title(I18n.get(key)).description(I18n.get(key + ".description"))
                .item(ModItems.BUILD_GUN.get(), true, false).addToIndex().register();
        }
        for (BuildingDef def : Buildings.all()) {
            Item item = ModItems.BUILDINGS.get(def.id());
            if (def.dev() || item == null) continue;
            helper.addTagToComponent(BuiltInRegistries.ITEM.getKey(item), tag(def.category()));
        }
    }

    @Override
    public void registerSharedText(SharedTextRegistrationHelper helper) {
        for (String key : SHARED) helper.registerSharedText(key, I18n.get("siftec.ponder.shared." + key));
    }
}
