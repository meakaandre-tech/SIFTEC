package com.meakaandre.siftec.equip;

import com.meakaandre.siftec.Siftec;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: the jump key is held in mid-air with a Jetpack on (or has been let go). */
public record JetThrustPayload(boolean on) implements CustomPacketPayload {
    public static final Type<JetThrustPayload> TYPE = new Type<>(Siftec.id("jet_thrust"));
    public static final StreamCodec<RegistryFriendlyByteBuf, JetThrustPayload> CODEC =
        StreamCodec.of((buf, payload) -> buf.writeBoolean(payload.on), buf -> new JetThrustPayload(buf.readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
