package com.meakaandre.siftec.cosmetic;

import com.meakaandre.siftec.Siftec;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server to clients: a player (by entity id) plays an emote. */
public record EmotePayload(int entity, String emote) implements CustomPacketPayload {
    public static final Type<EmotePayload> TYPE = new Type<>(Siftec.id("emote"));
    public static final StreamCodec<RegistryFriendlyByteBuf, EmotePayload> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.VAR_INT, EmotePayload::entity, ByteBufCodecs.STRING_UTF8, EmotePayload::emote, EmotePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
