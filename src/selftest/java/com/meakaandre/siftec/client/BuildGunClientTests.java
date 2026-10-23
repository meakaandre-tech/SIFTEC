package com.meakaandre.siftec.client;

import com.meakaandre.siftec.building.BuildingDef;
import com.meakaandre.siftec.building.BuildingTests;
import com.meakaandre.siftec.building.Category;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

import java.util.List;

/** The smoke run's Build Gun tab ({@link BuildingTests.Tab}, from {@code /siftec worldtest guntab}): picks a tab and a building. */
public final class BuildGunClientTests {
    private BuildGunClientTests() {
    }

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(BuildingTests.Tab.TYPE, (payload, context) -> Minecraft.getInstance().execute(() -> tab(payload)));
    }

    private static void tab(BuildingTests.Tab payload) {
        Minecraft mc = Minecraft.getInstance();
        BuildGunClient.category = Math.floorMod(payload.category(), Category.values().length);
        BuildGunClient.selected = !payload.building().isEmpty();
        List<BuildingDef> defs = BuildGunClient.list(mc);
        for (int i = 0; i < defs.size(); i++) if (defs.get(i).id().equals(payload.building())) BuildGunClient.picked[BuildGunClient.category] = i;
        BuildGunClient.dismantle = BuildGunClient.nudge = false;
        BuildGunClient.frozen = null;
    }
}
