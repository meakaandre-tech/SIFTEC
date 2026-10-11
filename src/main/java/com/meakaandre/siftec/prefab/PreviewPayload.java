package com.meakaandre.siftec.prefab;

import com.meakaandre.siftec.Siftec;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;

/**
 * Server to client: the server's verdict on the spot the player's HUB or Gateway hologram is at, with the land rules
 * only the server knows (claims, the spawn area, one Gateway per company). The client colours the hologram by it.
 */
public record PreviewPayload(int kind, BlockPos anchor, int facing, boolean ok, String key, List<String> args) implements CustomPacketPayload {
    public static final Type<PreviewPayload> TYPE = new Type<>(Siftec.id("prefab_preview"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PreviewPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeVarInt(p.kind);
            buf.writeBlockPos(p.anchor);
            buf.writeVarInt(p.facing);
            buf.writeBoolean(p.ok);
            buf.writeUtf(p.key);
            buf.writeVarInt(p.args.size());
            for (String a : p.args) buf.writeUtf(a);
        },
        buf -> {
            int kind = buf.readVarInt();
            BlockPos anchor = buf.readBlockPos();
            int facing = buf.readVarInt();
            boolean ok = buf.readBoolean();
            String key = buf.readUtf();
            int n = Math.min(8, buf.readVarInt());
            List<String> args = new ArrayList<>();
            for (int i = 0; i < n; i++) args.add(buf.readUtf());
            return new PreviewPayload(kind, anchor, facing, ok, key, args);
        });

    public static PreviewPayload of(Prefabs.Check check) {
        List<String> args = new ArrayList<>();
        String key = "";
        if (check.reason != null) {
            key = check.reason;
            for (Object a : check.args) args.add(String.valueOf(a));
        } else if (!check.blocked.isEmpty()) {
            key = check.noGround == check.blocked.size() ? "siftec.prefab.no_ground" : "siftec.prefab.blocked";
            args.add(String.valueOf(check.noGround == check.blocked.size() ? Prefabs.MAX_DROP : check.blocked.size()));
        }
        return new PreviewPayload(check.spot.kind().ordinal(), check.spot.anchor(), check.spot.facing().get2DDataValue(), check.ok(), key, args);
    }

    public Component message() {
        return Component.translatable(key, args.toArray());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
