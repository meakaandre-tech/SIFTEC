package com.meakaandre.siftec.equip;

import com.meakaandre.siftec.Siftec;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server to client: whether the Jetpack has fuel. The push itself happens on the client, which owns movement. */
public record JetFuelPayload(boolean ok) implements CustomPacketPayload {
    public static final Type<JetFuelPayload> TYPE = new Type<>(Siftec.id("jet_fuel"));
    public static final StreamCodec<RegistryFriendlyByteBuf, JetFuelPayload> CODEC =
        StreamCodec.of((buf, payload) -> buf.writeBoolean(payload.ok), buf -> new JetFuelPayload(buf.readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
