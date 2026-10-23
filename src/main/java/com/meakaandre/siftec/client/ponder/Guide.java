package com.meakaandre.siftec.client.ponder;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.building.BuildingDef;
import com.meakaandre.siftec.building.Buildings;
import com.meakaandre.siftec.client.BuildGunClient;
import com.meakaandre.siftec.guide.GuideTests;
import com.meakaandre.siftec.hub.Milestone;
import com.meakaandre.siftec.hub.Milestones;
import com.meakaandre.siftec.registry.ModItems;
import com.zurrtum.create.client.catnip.gui.ScreenOpener;
import com.zurrtum.create.client.ponder.foundation.PonderIndex;
import com.zurrtum.create.client.ponder.foundation.PonderScene;
import com.zurrtum.create.client.ponder.foundation.ui.PonderUI;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The guide on the client (work package 13): SIFTEC's Ponder plugin, opening a building's scene (the Build Gun's H
 * key), the guide items' and the Build Gun's tooltip lines, and the smoke run's checks of every scene and line.
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
        ClientPlayNetworking.registerGlobalReceiver(GuideTests.Control.TYPE, (payload, context) -> Minecraft.getInstance().execute(() -> control(payload)));
    }

    /** Opens a building's Ponder scene. */
    public static void open(BuildingDef def) {
        Item item = ModItems.BUILDINGS.get(def.id());
        if (item == null || !PonderIndex.getSceneAccess().doScenesExistForId(BuiltInRegistries.ITEM.getKey(item))) return;
        ScreenOpener.transitionTo(PonderUI.of(new ItemStack(item)));
    }

    private static void control(GuideTests.Control control) {
        switch (control.action()) {
            case "check" -> check();
            case "open" -> {
                BuildingDef def = Buildings.def(control.arg());
                if (def == null) report("FAIL open: no building " + control.arg());
                else open(def);
            }
            case "close" -> Minecraft.getInstance().gui.setScreen(null);
            default -> {
            }
        }
    }

    private static void report(String text) {
        Siftec.LOGGER.info("SELFTEST guide-client " + text);
    }

    /**
     * Builds every SIFTEC Ponder scene and looks up every line the guide shows: a scene that throws, comes out empty or
     * misses a line in the lang file is a failure.
     */
    private static void check() {
        int passed = 0, failed = 0;
        for (BuildingDef def : Buildings.all()) {
            Item item = ModItems.BUILDINGS.get(def.id());
            if (def.dev() || item == null) continue;
            Identifier key = BuiltInRegistries.ITEM.getKey(item);
            List<String> missing = new ArrayList<>();
            if (!Language.getInstance().has("siftec.ponder." + def.id() + ".header")) missing.add("header");
            int lines = BuildingScenes.lines(def).size();
            for (int n = 1; n <= lines; n++) if (!Language.getInstance().has("siftec.ponder." + def.id() + ".text_" + n)) missing.add("text_" + n);
            String result;
            boolean ok;
            try {
                List<PonderScene> scenes = PonderIndex.getSceneAccess().compile(key);
                int blocks = 0;
                for (PonderScene scene : scenes) {
                    var b = scene.getLevel().getBounds();
                    blocks = Math.max(blocks, b.getXSpan() * b.getYSpan() * b.getZSpan());
                }
                // a scene with lines and a building (a vehicle's scene is its plate and the vehicle)
                ok = scenes.size() == 1 && lines > 0 && missing.isEmpty() && blocks > 1;
                result = scenes.size() + " scene, " + lines + " lines, space " + blocks + (missing.isEmpty() ? "" : ", missing lang " + missing);
            } catch (RuntimeException e) {
                Siftec.LOGGER.error("SIFTEC: Ponder scene of {} failed", def.id(), e);
                ok = false;
                result = "threw " + e;
            }
            if (ok) passed++;
            else failed++;
            report((ok ? "PASS " : "FAIL ") + def.id() + " (" + result + ")");
        }
        List<String> shared = new ArrayList<>();
        for (String k : SiftecPonderPlugin.SHARED) if (!Language.getInstance().has("siftec.ponder.shared." + k)) shared.add(k);
        if (shared.isEmpty()) passed++;
        else failed++;
        report((shared.isEmpty() ? "PASS " : "FAIL ") + "shared lines" + (shared.isEmpty() ? "" : " missing " + shared));
        List<String> text = new ArrayList<>();
        List<Milestone> all = new ArrayList<>(Milestones.phases());
        for (int tier = 0; tier < Milestones.TIERS; tier++) all.addAll(Milestones.tier(tier));
        for (Milestone m : all) if (!Language.getInstance().has("siftec.milestone." + m.id() + ".desc")) text.add(m.id());
        if (text.isEmpty()) passed++;
        else failed++;
        report((text.isEmpty() ? "PASS " : "FAIL ") + "milestone text for " + all.size() + " milestones and phases" + (text.isEmpty() ? "" : ", missing " + text));
        report("done: " + passed + " passed, " + failed + " failed");
    }
}
