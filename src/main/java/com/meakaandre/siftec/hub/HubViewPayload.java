package com.meakaandre.siftec.hub;

import com.meakaandre.siftec.Siftec;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server to client: what the open HUB screen (menu {@code containerId}) shows. */
public record HubViewPayload(int containerId, HubView view) implements CustomPacketPayload {
    public static final Type<HubViewPayload> TYPE = new Type<>(Siftec.id("hub_view"));
    public static final StreamCodec<RegistryFriendlyByteBuf, HubViewPayload> STREAM_CODEC = StreamCodec.of(
        (buf, payload) -> {
            buf.writeVarInt(payload.containerId);
            payload.view.write(buf);
        },
        buf -> new HubViewPayload(buf.readVarInt(), HubView.read(buf)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
