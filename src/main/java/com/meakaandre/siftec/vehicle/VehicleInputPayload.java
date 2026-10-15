package com.meakaandre.siftec.vehicle;

import com.meakaandre.siftec.Siftec;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: a driver's keys, sent when they change (W and S throttle, A and D steer, Space brakes). */
public record VehicleInputPayload(boolean forward, boolean back, boolean left, boolean right, boolean brake) implements CustomPacketPayload {
    public static final VehicleInputPayload NONE = new VehicleInputPayload(false, false, false, false, false);
    public static final Type<VehicleInputPayload> TYPE = new Type<>(Siftec.id("vehicle_input"));
    public static final StreamCodec<RegistryFriendlyByteBuf, VehicleInputPayload> CODEC = StreamCodec.of(
        (buf, p) -> buf.writeByte((p.forward ? 1 : 0) | (p.back ? 2 : 0) | (p.left ? 4 : 0) | (p.right ? 8 : 0) | (p.brake ? 16 : 0)),
        buf -> {
            int b = buf.readByte();
            return new VehicleInputPayload((b & 1) != 0, (b & 2) != 0, (b & 4) != 0, (b & 8) != 0, (b & 16) != 0);
        });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
