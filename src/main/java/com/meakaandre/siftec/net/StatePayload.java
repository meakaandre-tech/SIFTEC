package com.meakaandre.siftec.net;

import com.meakaandre.siftec.Siftec;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;

/** What a client needs to know about its player's company: the speed cap and what is unlocked. */
public record StatePayload(int speedCap, List<String> done) implements CustomPacketPayload {
    public static final Type<StatePayload> TYPE = new Type<>(Siftec.id("state"));
    public static final StreamCodec<RegistryFriendlyByteBuf, StatePayload> STREAM_CODEC =
        StreamCodec.of(StatePayload::encode, StatePayload::decode);

    private static void encode(FriendlyByteBuf buf, StatePayload payload) {
        buf.writeVarInt(payload.speedCap);
        buf.writeVarInt(payload.done.size());
        for (String id : payload.done) buf.writeUtf(id);
    }

    private static StatePayload decode(FriendlyByteBuf buf) {
        int cap = buf.readVarInt();
        int n = buf.readVarInt();
        List<String> done = new ArrayList<>(n);
        for (int i = 0; i < n; i++) done.add(buf.readUtf());
        return new StatePayload(cap, done);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
