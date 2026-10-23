package com.meakaandre.siftec.client.map;

import com.meakaandre.siftec.map.MapMarker;
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
            case "open" -> {
                MapScreen screen = new MapScreen();
                screen.centre(p.x(), p.z(), p.zoom());
                MapClient.show(screen);
            }
            case "close" -> {
                if (mc.gui.screen() instanceof MapScreen) mc.gui.setScreen(null);
            }
            case "hide" -> MapClient.HIDDEN.add(p.arg());
            case "show" -> MapClient.HIDDEN.remove(p.arg());
            case "minimap" -> MapClient.minimapOn = p.arg().equals("on");
            case "pin_marker" -> {
                for (MapMarker m : MapClient.markers) if (m.name.equals(p.arg())) MapClient.pin(MapClient.markerPin(m));
            }
            case "edit" -> {
                if (mc.gui.screen() instanceof MapScreen screen) screen.newMarker(p.x(), p.z());
            }
            default -> {
            }
        }
    }
}
