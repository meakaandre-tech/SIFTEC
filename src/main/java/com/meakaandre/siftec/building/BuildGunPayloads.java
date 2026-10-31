package com.meakaandre.siftec.building;

import com.meakaandre.siftec.Siftec;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What the Build Gun's client and server tell each other. */
public final class BuildGunPayloads {
    /** Longest building id a client may send. */
    private static final int MAX_ID_LENGTH = 64;
    /** Most buildings one mass dismantle takes down (Satisfactory's 50). */
    public static final int MAX_DISMANTLE = 50;
    /** Longest cost key (an item id or #tag) in the carried counts. */
    private static final int MAX_KEY_LENGTH = 256;

    private BuildGunPayloads() {
    }

    private static void writeStrings(RegistryFriendlyByteBuf buf, List<String> list) {
        buf.writeVarInt(list.size());
        for (String s : list) buf.writeUtf(s);
    }

    private static List<String> readStrings(RegistryFriendlyByteBuf buf, int max, int maxLength) {
        int n = buf.readVarInt();
        if (n < 0 || n > max) throw new IllegalArgumentException("too many entries: " + n);
        List<String> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(buf.readUtf(maxLength));
        return out;
    }

    /**
     * Client to server: what the player has picked and where the hologram is (anchor, facing, flip), or that the
     * gun is in dismantle mode. Sent whenever it changes, so the server can colour the hologram; building happens only
     * on a {@link Place}.
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
            buf -> new Select(buf.readUtf(MAX_ID_LENGTH), buf.readBoolean(), buf.readBoolean(), buf.readBlockPos(), buf.readVarInt() & 3, buf.readBoolean()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Client to server, on a left click in build mode: build this building at this hologram. The server checks it all again. */
    public record Place(String building, BlockPos anchor, int facing, boolean flip) implements CustomPacketPayload {
        public static final Type<Place> TYPE = new Type<>(Siftec.id("build_gun_place"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Place> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeUtf(p.building);
                buf.writeBlockPos(p.anchor);
                buf.writeVarInt(p.facing);
                buf.writeBoolean(p.flip);
            },
            buf -> new Place(buf.readUtf(MAX_ID_LENGTH), buf.readBlockPos(), buf.readVarInt() & 3, buf.readBoolean()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Client to server, after left click was held for a second in dismantle mode: take these buildings down (a block of
     * each), or with {@code vehicle} the vehicle the player aims at. The server checks each one as if it were aimed at.
     */
    public record Dismantle(List<BlockPos> targets, boolean vehicle) implements CustomPacketPayload {
        public static final Type<Dismantle> TYPE = new Type<>(Siftec.id("build_gun_dismantle"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Dismantle> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeVarInt(p.targets.size());
                for (BlockPos pos : p.targets) buf.writeBlockPos(pos);
                buf.writeBoolean(p.vehicle);
            },
            buf -> {
                int n = buf.readVarInt();
                if (n < 0 || n > MAX_DISMANTLE) throw new IllegalArgumentException("too many buildings to dismantle: " + n);
                List<BlockPos> targets = new ArrayList<>(n);
                for (int i = 0; i < n; i++) targets.add(buf.readBlockPos());
                return new Dismantle(targets, buf.readBoolean());
            });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Client to server: put a building (or "" for none) into a slot of one of the player's build hotbars. */
    public record Bind(int bar, int slot, String building) implements CustomPacketPayload {
        public static final Type<Bind> TYPE = new Type<>(Siftec.id("build_gun_bind"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Bind> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeVarInt(p.bar);
                buf.writeVarInt(p.slot);
                buf.writeUtf(p.building);
            },
            buf -> new Bind(buf.readVarInt(), buf.readVarInt(), buf.readUtf(MAX_ID_LENGTH)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Client to server: the build hotbar the player switched to (Alt + scroll), kept with the bars. */
    public record Page(int bar) implements CustomPacketPayload {
        public static final Type<Page> TYPE = new Type<>(Siftec.id("build_gun_page"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Page> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> buf.writeVarInt(p.bar), buf -> new Page(buf.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server to client: the player's build hotbars (bar after bar, {@link BuildHotbars#SLOTS} slots each) and the one shown. */
    public record Hotbars(int page, List<String> slots) implements CustomPacketPayload {
        public static final Type<Hotbars> TYPE = new Type<>(Siftec.id("build_gun_hotbars"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Hotbars> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeVarInt(p.page);
                writeStrings(buf, p.slots);
            },
            buf -> new Hotbars(buf.readVarInt(), readStrings(buf, BuildHotbars.BARS * BuildHotbars.SLOTS, MAX_ID_LENGTH)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Server to client, while the gun is held: the buildings the player's company has unlocked (all of them for a
     * creative player), which the build menu and hotbars show, and how many of each building cost the player carries,
     * backpack included (the client does not see the backpack), keyed by the cost's item or tag.
     */
    public record GunState(List<String> unlocked, Map<String, Integer> carried) implements CustomPacketPayload {
        public static final Type<GunState> TYPE = new Type<>(Siftec.id("build_gun_state"));
        /** Most entries either list may have. */
        private static final int MAX_ENTRIES = 4096;
        public static final StreamCodec<RegistryFriendlyByteBuf, GunState> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                writeStrings(buf, p.unlocked);
                buf.writeVarInt(p.carried.size());
                for (Map.Entry<String, Integer> e : p.carried.entrySet()) {
                    buf.writeUtf(e.getKey());
                    buf.writeVarInt(e.getValue());
                }
            },
            buf -> {
                List<String> unlocked = readStrings(buf, MAX_ENTRIES, MAX_ID_LENGTH);
                int n = buf.readVarInt();
                if (n < 0 || n > MAX_ENTRIES) throw new IllegalArgumentException("too many cost counts: " + n);
                Map<String, Integer> carried = new LinkedHashMap<>();
                for (int i = 0; i < n; i++) carried.put(buf.readUtf(MAX_KEY_LENGTH), buf.readVarInt());
                return new GunState(unlocked, carried);
            });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Server to client: the server's verdict on the hologram's spot (with what only the server knows: unlocks,
     * company land), or in dismantle mode the box of the building aimed at. For a building, {@code min} is the anchor it
     * really goes to and {@code from} the anchor the client sent: they differ when the server snapped a miner onto the
     * node near it (only the server knows where the nodes are), and the client then shows its hologram at {@code min}.
     * {@code target} is the id of the building (or vehicle kind) under the crosshair and {@code at} a block of it, or ""
     * if there is none: dismantle mode selects it, and the eyedropper picks it in build mode.
     */
    public record View(boolean ok, Component message, boolean hasBox, BlockPos min, BlockPos max, BlockPos from, String target,
                       BlockPos at) implements CustomPacketPayload {
        public static final Type<View> TYPE = new Type<>(Siftec.id("build_gun_view"));
        public static final StreamCodec<RegistryFriendlyByteBuf, View> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeBoolean(p.ok);
                ComponentSerialization.STREAM_CODEC.encode(buf, p.message);
                buf.writeBoolean(p.hasBox);
                buf.writeBlockPos(p.min);
                buf.writeBlockPos(p.max);
                buf.writeBlockPos(p.from);
                buf.writeUtf(p.target);
                buf.writeBlockPos(p.at);
            },
            buf -> new View(buf.readBoolean(), ComponentSerialization.STREAM_CODEC.decode(buf), buf.readBoolean(), buf.readBlockPos(), buf.readBlockPos(),
                buf.readBlockPos(), buf.readUtf(MAX_ID_LENGTH), buf.readBlockPos()));

        public static View of(boolean ok, Component message, @Nullable BlockPos min, @Nullable BlockPos max) {
            BlockPos at = min == null ? BlockPos.ZERO : min;
            return new View(ok, message, min != null && max != null, at, max == null ? BlockPos.ZERO : max, at, "", BlockPos.ZERO);
        }

        /** The same verdict, for a hologram the client sent at {@code sent}. */
        public View from(BlockPos sent) {
            return new View(ok, message, hasBox, min, max, sent.immutable(), target, at);
        }

        /** The same verdict, naming the building (or vehicle kind) under the crosshair and a block of it. */
        public View aimedAt(String id, BlockPos pos) {
            return new View(ok, message, hasBox, min, max, from, id, pos.immutable());
        }

        /** True if the server moved the spot the client sent (a miner snapped onto a node). */
        public boolean snapped() {
            return hasBox && !min.equals(from);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
