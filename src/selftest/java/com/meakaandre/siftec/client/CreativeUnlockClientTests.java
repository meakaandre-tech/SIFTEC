package com.meakaandre.siftec.client;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.building.BuildMenu;
import com.meakaandre.siftec.building.BuildingDef;
import com.meakaandre.siftec.building.Buildings;
import com.meakaandre.siftec.command.CreativeUnlockTests;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The build menu in creative ({@code /siftec selftest creativeunlocks menu}): as if the server had sent the company's
 * partial unlock list, it opens the real build menu for the creative player and checks that every building is listed,
 * in every category, and can be picked; the same list for a survival player still shows only what is unlocked. The
 * menu stays open for the smoke script's screenshot until {@code menu done} closes it and puts the real list back. Logs
 * "SELFTEST creative-unlocks-client PASS/FAIL ...", "... checked" and "... done: N passed, M failed".
 */
public final class CreativeUnlockClientTests {
    /** What the server is made to have sent: two buildings of the company's. */
    private static final Set<String> PARTIAL = Set.of("hub", "constructor");
    private static int passed, failed;
    private static Set<String> realUnlocked;

    private CreativeUnlockClientTests() {
    }

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(CreativeUnlockTests.MenuCheck.TYPE,
            (payload, context) -> Minecraft.getInstance().execute(() -> {
                if (payload.done()) done();
                else check();
            }));
    }

    private static void check() {
        Minecraft mc = Minecraft.getInstance();
        try {
            if (realUnlocked == null) realUnlocked = BuildGunClient.unlocked;
            BuildGunClient.unlocked = new HashSet<>(PARTIAL);
            Set<String> every = new HashSet<>();
            for (BuildingDef def : Buildings.all()) every.add(def.id());
            List<String> everySection = new ArrayList<>();
            for (String s : BuildMenu.sections()) {
                for (BuildingDef def : BuildMenu.visible(every, "")) {
                    if (BuildMenu.place(def).section().equals(s)) {
                        everySection.add(s);
                        break;
                    }
                }
            }
            boolean creative = mc.player != null && mc.player.hasInfiniteMaterials();
            expect("the player is in creative", creative, mc.player == null ? "no player" : "");

            // the survival view of the same list: only the two (unchanged)
            BuildMenuScreen survival = new BuildMenuScreen(false);
            mc.gui.setScreen(survival);
            Set<String> survivalIds = new HashSet<>(survival.unlocked());
            expect("a survival player's menu still lists only what the server said is unlocked", survivalIds.equals(PARTIAL), survivalIds.toString());

            // the real menu, as the creative player opens it with Q
            BuildMenuScreen screen = new BuildMenuScreen();
            mc.gui.setScreen(screen);
            Set<String> ids = new HashSet<>(screen.unlocked());
            List<String> sections = screen.sections();
            List<String> missing = new ArrayList<>();
            for (String id : every) if (!ids.contains(id)) missing.add(id);
            expect("after a partial unlock list, the creative player's build menu lists every building",
                missing.isEmpty() && ids.size() == every.size(), ids.size() + " of " + every.size() + (missing.isEmpty() ? "" : ", missing " + missing));
            expect("every category is there", sections.equals(everySection), sections + " of " + everySection);
            List<String> unpickable = new ArrayList<>();
            for (String id : every) if (!BuildGunClient.available(mc, id)) unpickable.add(id);
            expect("the creative player may pick every building", unpickable.isEmpty(), unpickable.toString());
        } catch (RuntimeException e) {
            Siftec.LOGGER.error("SELFTEST creative-unlocks-client threw", e);
            expect("the checks run to the end", false, e.toString());
        }
        Siftec.LOGGER.info("SELFTEST creative-unlocks-client checked");
    }

    private static void done() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() instanceof BuildMenuScreen) mc.gui.setScreen(null);
        if (realUnlocked != null) BuildGunClient.unlocked = realUnlocked;
        realUnlocked = null;
        Siftec.LOGGER.info("SELFTEST creative-unlocks-client done: {} passed, {} failed", passed, failed);
    }

    private static void expect(String what, boolean ok, String detail) {
        if (ok) passed++;
        else failed++;
        Siftec.LOGGER.info("SELFTEST creative-unlocks-client {} {}{}", ok ? "PASS" : "FAIL", what, detail.isEmpty() ? "" : " (" + detail + ")");
    }
}
