package com.meakaandre.siftec.client;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.drone.DroneEntity;
import com.meakaandre.siftec.vehicle.VehicleEntity;
import com.meakaandre.siftec.vehicle.VehicleInputPayload;
import com.meakaandre.siftec.vehicle.VehicleRecordPayload;
import com.meakaandre.siftec.vehicle.Vehicles;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/**
 * Vehicles on the client: their renderer, and the driver's keys sent to the server (the server moves the vehicle).
 * W and S throttle, A and D steer, Space brakes, V records a route (changeable under SIFTEC Vehicles).
 */
public final class VehicleClient {
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Siftec.id("vehicles"));
    public static final KeyMapping RECORD = new KeyMapping("key.siftec.vehicle.record", InputConstants.KEY_V, CATEGORY);
    private static @Nullable VehicleInputPayload sent;
    private static int since;

    private VehicleClient() {
    }

    public static void register() {
        for (var type : Vehicles.types().values()) EntityRendererRegistry.register(type, VehicleRenderer::new);
        EntityRendererRegistry.register(DroneEntity.TYPE, DroneRenderer::new);
        KeyMappingHelper.registerKeyMapping(RECORD);
        ClientTickEvents.END_CLIENT_TICK.register(VehicleClient::tick);
    }

    private static void tick(Minecraft mc) {
        if (mc.player == null || !ClientPlayNetworking.canSend(VehicleInputPayload.TYPE)) {
            sent = null;
            return;
        }
        boolean driving = mc.player.getVehicle() instanceof VehicleEntity;
        VehicleInputPayload keys = !driving || mc.gui.screen() != null ? VehicleInputPayload.NONE
            : new VehicleInputPayload(mc.options.keyUp.isDown(), mc.options.keyDown.isDown(), mc.options.keyLeft.isDown(), mc.options.keyRight.isDown(),
                mc.options.keyJump.isDown());
        // say so when the keys change, and once a second while driving so a lost packet does not leave it running
        if (!keys.equals(sent) || (driving && ++since >= 20)) {
            if (driving || sent != null) ClientPlayNetworking.send(keys);
            sent = driving ? keys : null;
            since = 0;
        }
        while (RECORD.consumeClick()) {
            if (driving) ClientPlayNetworking.send(new VehicleRecordPayload());
        }
    }
}
