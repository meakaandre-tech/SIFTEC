package com.meakaandre.siftec.client;

import com.meakaandre.siftec.equip.JetThrustPayload;
import com.meakaandre.siftec.net.ClientState;
import com.meakaandre.siftec.registry.ModItems;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.phys.Vec3;

/** The Jetpack's push. Holding jump in mid-air lifts the player while the server says there is fuel. */
public final class JetpackClient {
    private static final double LIFT = 0.12, MAX_RISE = 0.55, PUSH = 0.03, MAX_RUN = 0.7;
    private static boolean sent;
    private static int since;

    private JetpackClient() {
    }

    public static void tick(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null) {
            sent = false;
            return;
        }
        boolean want = client.screen == null && client.options.keyJump.isDown() && !player.onGround() && !player.getAbilities().flying
            && !player.isPassenger() && player.getItemBySlot(EquipmentSlot.CHEST).is(ModItems.JETPACK.get());
        if (want && ClientState.jetFuel) {
            Vec3 v = player.getDeltaMovement();
            double x = v.x, z = v.z;
            if (client.options.keyUp.isDown()) {
                Vec3 look = player.getLookAngle();
                x += look.x * PUSH;
                z += look.z * PUSH;
                double run = Math.sqrt(x * x + z * z);
                if (run > MAX_RUN) {
                    x *= MAX_RUN / run;
                    z *= MAX_RUN / run;
                }
            }
            player.setDeltaMovement(x, Math.min(v.y + LIFT, MAX_RISE), z);
            player.resetFallDistance();
        }
        // say so when it starts and stops, and keep saying so twice a second while it lasts
        if (want != sent || (want && ++since >= 10)) {
            ClientPlayNetworking.send(new JetThrustPayload(want));
            sent = want;
            since = 0;
        }
    }
}
