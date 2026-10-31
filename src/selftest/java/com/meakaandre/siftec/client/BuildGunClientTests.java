package com.meakaandre.siftec.client;

import com.meakaandre.siftec.building.BuildingTests;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

/**
 * The smoke run's Build Gun pick ({@link BuildingTests.Tab}, from {@code /siftec worldtest guntab}): picks a building
 * for the hologram, as the build menu would, or none. Key presses under the CI's virtual display cannot aim at the menu.
 */
public final class BuildGunClientTests {
    private BuildGunClientTests() {
    }

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(BuildingTests.Tab.TYPE, (payload, context) -> Minecraft.getInstance().execute(() -> tab(payload)));
    }

    private static void tab(BuildingTests.Tab payload) {
        if (payload.building().isEmpty()) {
            BuildGunClient.picked = "";
            BuildGunClient.slot = -1;
        } else {
            BuildGunClient.pick(payload.building());
        }
    }
}
