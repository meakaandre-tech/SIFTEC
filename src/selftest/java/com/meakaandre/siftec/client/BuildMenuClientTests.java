package com.meakaandre.siftec.client;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.building.BuildGunPayloads;
import com.meakaandre.siftec.building.BuildGunUiTests;
import com.meakaandre.siftec.building.BuildMenu;
import com.meakaandre.siftec.building.BuildingDef;
import com.meakaandre.siftec.building.Buildings;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The build menu on the client ({@code buildgun-client}): driven by {@link BuildGunUiTests.MenuCheck} from
 * {@code /siftec worldtest buildmenu}, it opens the real menu with a made-up set of unlocked buildings and checks that
 * only those show, that the search filters them, and that the dismantle filter keeps one type; and whenever the menu is
 * open it logs where its search box and building cells are on the window ("SELFTEST buildmenu search x y", "SELFTEST
 * buildmenu cell id x y"), for the smoke script to point at.
 */
public final class BuildMenuClientTests {
    private static String laidOut = "";

    private BuildMenuClientTests() {
    }

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(BuildGunUiTests.MenuCheck.TYPE, (payload, context) -> Minecraft.getInstance().execute(BuildMenuClientTests::check));
        ClientTickEvents.END_CLIENT_TICK.register(BuildMenuClientTests::logLayout);
    }

    private static List<String> ids(List<BuildingDef> defs) {
        List<String> out = new ArrayList<>();
        for (BuildingDef def : defs) out.add(def.id());
        return out;
    }

    private static void check() {
        Minecraft mc = Minecraft.getInstance();
        int[] count = new int[2];
        Set<String> realUnlocked = BuildGunClient.unlocked;
        try {
            Set<String> some = Set.of("hub", "constructor", "smelter", "power_pole_mk1");
            List<String> all = ids(BuildMenu.visible(some, ""));
            expect(count, "the menu's rules list exactly the unlocked buildings, in menu order",
                all.equals(List.of("hub", "smelter", "constructor", "power_pole_mk1")), all.toString());
            List<String> poles = ids(BuildMenu.visible(some, "Pole"));
            expect(count, "a search for 'Pole' keeps only the Power Pole Mk.1", poles.equals(List.of("power_pole_mk1")), poles.toString());
            List<String> none = ids(BuildMenu.visible(some, "zzz"));
            expect(count, "a search matching nothing shows nothing", none.isEmpty(), none.toString());
            List<String> locked = ids(BuildMenu.visible(some, "miner"));
            expect(count, "a locked building stays hidden from the search (Miner Mk.1)", locked.isEmpty(), locked.toString());

            // the real screen, with only these unlocked
            BuildGunClient.unlocked = new HashSet<>(some);
            BuildMenuScreen screen = new BuildMenuScreen();
            mc.gui.setScreen(screen);
            Set<String> sections = new HashSet<>(screen.sections());
            expect(count, "the screen lists only the categories with something unlocked (Special, Production, Power)",
                sections.equals(Set.of("special", "production", "power")), sections.toString());
            List<String> everywhere = new ArrayList<>();
            screen.searchFor("o");
            for (BuildMenuScreen.Cell c : screen.cells()) everywhere.add(c.def().id());
            expect(count, "searching shows matches from every category, and only unlocked ones",
                Set.copyOf(everywhere).equals(Set.of("constructor", "power_pole_mk1")), everywhere.toString());
            screen.searchFor("smel");
            List<String> smelter = new ArrayList<>();
            for (BuildMenuScreen.Cell c : screen.cells()) smelter.add(c.def().id());
            expect(count, "the screen's search box filters the grid to the Smelter", smelter.equals(List.of("smelter")), smelter.toString());
            mc.gui.setScreen(null);

            // the dismantle filter (G) keeps only the type it was set to
            BuildGunClient.clearSelection();
            BuildGunClient.filter = "";
            BuildGunClient.select(view("test_building", new BlockPos(1, 0, 0)));
            BuildGunClient.select(view("constructor", new BlockPos(9, 0, 0)));
            BuildGunClient.select(view("test_building", new BlockPos(1, 0, 0)));
            int both = BuildGunClient.selection.size();
            BuildGunClient.filter = "test_building";
            BuildGunClient.selection.removeIf(p -> !p.id().equals(BuildGunClient.filter));
            BuildGunClient.select(view("constructor", new BlockPos(20, 0, 0)));
            expect(count, "a mass selection takes each building once, and the filter keeps only its type",
                both == 2 && BuildGunClient.selection.size() == 1 && BuildGunClient.selection.getFirst().id().equals("test_building"),
                both + " then " + BuildGunClient.selection.size());
            BuildGunClient.clearSelection();
            BuildGunClient.filter = "";

            List<String> noPlace = new ArrayList<>();
            for (BuildingDef def : Buildings.all()) if (BuildingVisuals.icon(def).isEmpty()) noPlace.add(def.id());
            expect(count, "every building has an icon for the menu and the hotbar", noPlace.isEmpty(), noPlace.toString());
        } catch (RuntimeException e) {
            Siftec.LOGGER.error("SELFTEST buildgun-client threw", e);
            expect(count, "the checks run to the end", false, e.toString());
        } finally {
            BuildGunClient.unlocked = realUnlocked;
            if (mc.gui.screen() instanceof BuildMenuScreen) mc.gui.setScreen(null);
        }
        Siftec.LOGGER.info("SELFTEST buildgun-client done: {} passed, {} failed", count[0], count[1]);
    }

    private static BuildGunPayloads.View view(String id, BlockPos at) {
        return BuildGunPayloads.View.of(true, Component.empty(), at, at).aimedAt(id, at);
    }

    private static void expect(int[] count, String what, boolean ok, String detail) {
        count[ok ? 0 : 1]++;
        Siftec.LOGGER.info("SELFTEST buildgun-client {} {}{}", ok ? "PASS" : "FAIL", what, detail.isEmpty() ? "" : " (" + detail + ")");
    }

    /** While the menu is open: where its search box and cells are, in window pixels, whenever that changes. */
    private static void logLayout(Minecraft mc) {
        if (!(mc.gui.screen() instanceof BuildMenuScreen screen)) {
            laidOut = "";
            return;
        }
        int scale = mc.getWindow().getGuiScale();
        StringBuilder sig = new StringBuilder();
        List<String> lines = new ArrayList<>();
        int[] s = screen.searchCentre();
        lines.add("search " + s[0] * scale + " " + s[1] * scale);
        for (BuildMenuScreen.Cell c : screen.cells()) {
            int half = (BuildMenuScreen.CELL - 2) / 2;
            lines.add("cell " + c.def().id() + " " + (c.x() + half) * scale + " " + (c.y() + half) * scale);
        }
        for (String l : lines) sig.append(l).append(';');
        if (sig.toString().equals(laidOut)) return;
        laidOut = sig.toString();
        for (String l : lines) Siftec.LOGGER.info("SELFTEST buildmenu {}", l);
    }
}
