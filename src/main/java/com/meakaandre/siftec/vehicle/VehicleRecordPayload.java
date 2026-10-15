package com.meakaandre.siftec.vehicle;

import com.meakaandre.siftec.Siftec;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: the driver pressed the record key (start recording a route, or finish it). */
public record VehicleRecordPayload() implements CustomPacketPayload {
    public static final Type<VehicleRecordPayload> TYPE = new Type<>(Siftec.id("vehicle_record"));
    public static final StreamCodec<RegistryFriendlyByteBuf, VehicleRecordPayload> CODEC = StreamCodec.of((buf, p) -> {
    }, buf -> new VehicleRecordPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
