package com.meakaandre.siftec.client.map;

import com.meakaandre.mapmod.client.map.MapClient;
import com.meakaandre.mapmod.client.map.MapScreen;
import com.meakaandre.mapmod.map.MapMarker;
import com.meakaandre.siftec.map.MapTests;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

/**
 * The smoke run's remote control of the map ({@link MapTests.Control}, from {@code /siftec worldtest map}): opens or
 * closes the map, centred and zoomed, or switches a filter, since key presses under the CI's virtual display are not
 * reliable enough.
 */
public final class MapClientTests {
    private MapClientTests() {
    }

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(MapTests.Control.TYPE, (p, c) -> Minecraft.getInstance().execute(() -> control(p)));
    }

    private static void control(MapTests.Control p) {
        Minecraft mc = Minecraft.getInstance();
        switch (p.action()) {
            case OPEN -> {
                MapScreen screen = new MapScreen();
                screen.centre(p.x(), p.z(), p.zoom());
                MapClient.show(screen);
            }
            case CLOSE -> {
                if (mc.gui.screen() instanceof MapScreen) mc.gui.setScreen(null);
            }
            case HIDE -> MapClient.HIDDEN.add(p.arg());
            case SHOW -> MapClient.HIDDEN.remove(p.arg());
            case PIN_MARKER -> {
                for (MapMarker m : MapClient.markers) if (m.name.equals(p.arg())) MapClient.pin(MapClient.markerPin(m));
            }
            case EDIT -> {
                if (mc.gui.screen() instanceof MapScreen screen) screen.newMarker(p.x(), p.z());
            }
            default -> {
            }
        }
    }
}
