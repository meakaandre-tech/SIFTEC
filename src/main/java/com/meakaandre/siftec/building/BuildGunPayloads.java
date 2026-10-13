package com.meakaandre.siftec.building;

import com.meakaandre.siftec.Siftec;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.jspecify.annotations.Nullable;

/** What the Build Gun's client and server tell each other. */
public final class BuildGunPayloads {
    private BuildGunPayloads() {
    }

    /**
     * Client to server: what the player has picked and where the hologram is (anchor, facing, flip), or that the
     * gun is in dismantle mode. Sent whenever it changes; the server checks it again before building.
     */
    public record Select(String building, boolean dismantle, boolean hasSpot, BlockPos anchor, int facing, boolean flip) implements CustomPacketPayload {
        public static final Type<Select> TYPE = new Type<>(Siftec.id("build_gun_select"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Select> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeUtf(p.building);
                buf.writeBoolean(p.dismantle);
                buf.writeBoolean(p.hasSpot);
                buf.writeBlockPos(p.anchor);
                buf.writeVarInt(p.facing);
                buf.writeBoolean(p.flip);
            },
            buf -> new Select(buf.readUtf(64), buf.readBoolean(), buf.readBoolean(), buf.readBlockPos(), buf.readVarInt() & 3, buf.readBoolean()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Server to client: the server's verdict on the hologram's spot (with what only the server knows: unlocks,
     * company land), or in dismantle mode the box of the building aimed at.
     */
    public record View(boolean ok, Component message, boolean hasBox, BlockPos min, BlockPos max) implements CustomPacketPayload {
        public static final Type<View> TYPE = new Type<>(Siftec.id("build_gun_view"));
        public static final StreamCodec<RegistryFriendlyByteBuf, View> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeBoolean(p.ok);
                ComponentSerialization.STREAM_CODEC.encode(buf, p.message);
                buf.writeBoolean(p.hasBox);
                buf.writeBlockPos(p.min);
                buf.writeBlockPos(p.max);
            },
            buf -> new View(buf.readBoolean(), ComponentSerialization.STREAM_CODEC.decode(buf), buf.readBoolean(), buf.readBlockPos(), buf.readBlockPos()));

        public static View of(boolean ok, Component message, @Nullable BlockPos min, @Nullable BlockPos max) {
            return new View(ok, message, min != null && max != null, min == null ? BlockPos.ZERO : min, max == null ? BlockPos.ZERO : max);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Server to client, from the smoke run's {@code /siftec worldtest guntab}: puts the gun on a tab, with a building
     * picked or with nothing picked (an empty building). Key presses under the CI's virtual display are not reliable
     * enough to count Tabs.
     */
    public record Tab(int category, String building) implements CustomPacketPayload {
        public static final Type<Tab> TYPE = new Type<>(Siftec.id("build_gun_tab"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Tab> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeVarInt(p.category);
                buf.writeUtf(p.building);
            },
            buf -> new Tab(buf.readVarInt(), buf.readUtf(64)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
