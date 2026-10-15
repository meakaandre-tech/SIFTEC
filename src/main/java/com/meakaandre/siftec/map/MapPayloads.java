package com.meakaandre.siftec.map;

import com.meakaandre.siftec.Siftec;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;

/** What the map's server and clients tell each other. */
public final class MapPayloads {
    private MapPayloads() {
    }

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type(String id) {
        return new CustomPacketPayload.Type<>(Siftec.id(id));
    }

    /** Server to client on joining: which map this is, so the client's disk cache keeps each world's tiles apart. */
    public record Hello(String mapId) implements CustomPacketPayload {
        public static final Type<Hello> TYPE = type("map_hello");
        public static final StreamCodec<RegistryFriendlyByteBuf, Hello> CODEC = StreamCodec.of((buf, p) -> buf.writeUtf(p.mapId), buf -> new Hello(buf.readUtf(64)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** One region's fog bits. */
    public record FogRegion(long region, long[] bits) {
    }

    /** Server to client: the company's fog for whole regions; {@code replace} drops all fog the client held first. */
    public record Fog(boolean replace, List<FogRegion> regions) implements CustomPacketPayload {
        public static final Type<Fog> TYPE = type("map_fog");
        public static final StreamCodec<RegistryFriendlyByteBuf, Fog> CODEC = StreamCodec.of((buf, p) -> {
            buf.writeBoolean(p.replace);
            buf.writeVarInt(p.regions.size());
            for (FogRegion r : p.regions) {
                buf.writeLong(r.region);
                for (long l : r.bits) buf.writeLong(l);
            }
        }, buf -> {
            boolean replace = buf.readBoolean();
            int n = buf.readVarInt();
            List<FogRegion> regions = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                long region = buf.readLong();
                long[] bits = new long[MapGrid.FOG_LONGS];
                for (int j = 0; j < bits.length; j++) bits[j] = buf.readLong();
                regions.add(new FogRegion(region, bits));
            }
            return new Fog(replace, regions);
        });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server to client: chunks a company member has just explored. */
    public record FogDelta(long[] chunks) implements CustomPacketPayload {
        public static final Type<FogDelta> TYPE = type("map_fog_delta");
        public static final StreamCodec<RegistryFriendlyByteBuf, FogDelta> CODEC = StreamCodec.of((buf, p) -> buf.writeLongArray(p.chunks),
            buf -> new FogDelta(buf.readLongArray()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** What the map shows in one region. */
    public record RegionPois(long region, List<MapPoi> pois) {
    }

    /** Server to client: the points of interest of some regions, each replacing what the client held for it. */
    public record Pois(List<RegionPois> regions) implements CustomPacketPayload {
        public static final Type<Pois> TYPE = type("map_pois");
        public static final StreamCodec<RegistryFriendlyByteBuf, Pois> CODEC = StreamCodec.of((buf, p) -> {
            buf.writeVarInt(p.regions.size());
            for (RegionPois r : p.regions) {
                buf.writeLong(r.region);
                buf.writeVarInt(r.pois.size());
                for (MapPoi poi : r.pois) poi.write(buf);
            }
        }, buf -> {
            int n = buf.readVarInt();
            List<RegionPois> regions = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                long region = buf.readLong();
                int m = buf.readVarInt();
                List<MapPoi> pois = new ArrayList<>(m);
                for (int j = 0; j < m; j++) pois.add(MapPoi.read(buf));
                regions.add(new RegionPois(region, pois));
            }
            return new Pois(regions);
        });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server to client: a region's terrain, packed as {@link MapTile#pack()} (its version is inside). */
    public record Tile(long region, byte[] data) implements CustomPacketPayload {
        public static final Type<Tile> TYPE = type("map_tile");
        public static final StreamCodec<RegistryFriendlyByteBuf, Tile> CODEC = StreamCodec.of((buf, p) -> {
            buf.writeLong(p.region);
            buf.writeByteArray(p.data);
        }, buf -> new Tile(buf.readLong(), buf.readByteArray(1 << 20)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Client to server: regions the client wants, each with the version it already holds (0 for none). */
    public record TileRequest(long[] regions, long[] versions) implements CustomPacketPayload {
        public static final Type<TileRequest> TYPE = type("map_tile_request");
        public static final StreamCodec<RegistryFriendlyByteBuf, TileRequest> CODEC = StreamCodec.of((buf, p) -> {
            buf.writeLongArray(p.regions);
            buf.writeLongArray(p.versions);
        }, buf -> new TileRequest(buf.readLongArray(), buf.readLongArray()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server to client: every marker the player sees. */
    public record Markers(List<MapMarker> markers) implements CustomPacketPayload {
        public static final Type<Markers> TYPE = type("map_markers");
        public static final StreamCodec<RegistryFriendlyByteBuf, Markers> CODEC = StreamCodec.of((buf, p) -> {
            buf.writeVarInt(p.markers.size());
            for (MapMarker m : p.markers) m.write(buf);
        }, buf -> {
            int n = buf.readVarInt();
            List<MapMarker> list = new ArrayList<>(n);
            for (int i = 0; i < n; i++) list.add(MapMarker.read(buf));
            return new Markers(list);
        });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static final int ADD = 0, UPDATE = 1, DELETE = 2;

    /** Client to server: place, change or remove a marker. The server checks who may. */
    public record MarkerEdit(int op, MapMarker marker) implements CustomPacketPayload {
        public static final Type<MarkerEdit> TYPE = type("map_marker_edit");
        public static final StreamCodec<RegistryFriendlyByteBuf, MarkerEdit> CODEC = StreamCodec.of((buf, p) -> {
            buf.writeVarInt(p.op);
            p.marker.write(buf);
        }, buf -> new MarkerEdit(buf.readVarInt(), MapMarker.read(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** A company member online, as the compass and the map show them. */
    public record Mate(String name, double x, double y, double z, float yaw) {
        void write(FriendlyByteBuf buf) {
            buf.writeUtf(name);
            buf.writeDouble(x);
            buf.writeDouble(y);
            buf.writeDouble(z);
            buf.writeFloat(yaw);
        }

        static Mate read(FriendlyByteBuf buf) {
            return new Mate(buf.readUtf(32), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readFloat());
        }
    }

    /** Server to client, twice a second: the company's other members in the player's dimension. */
    public record Mates(List<Mate> mates) implements CustomPacketPayload {
        public static final Type<Mates> TYPE = type("map_mates");
        public static final StreamCodec<RegistryFriendlyByteBuf, Mates> CODEC = StreamCodec.of((buf, p) -> {
            buf.writeVarInt(p.mates.size());
            for (Mate m : p.mates) m.write(buf);
        }, buf -> {
            int n = buf.readVarInt();
            List<Mate> list = new ArrayList<>(n);
            for (int i = 0; i < n; i++) list.add(Mate.read(buf));
            return new Mates(list);
        });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Server to client, from the smoke run's {@code /siftec worldtest map}: opens or closes the map, centred and zoomed,
     * or switches a filter (key presses under the CI's virtual display are not reliable enough).
     */
    public record Control(String action, String arg, int x, int z, float zoom) implements CustomPacketPayload {
        public static final Type<Control> TYPE = type("map_control");
        public static final StreamCodec<RegistryFriendlyByteBuf, Control> CODEC = StreamCodec.of((buf, p) -> {
            buf.writeUtf(p.action);
            buf.writeUtf(p.arg);
            buf.writeVarInt(p.x);
            buf.writeVarInt(p.z);
            buf.writeFloat(p.zoom);
        }, buf -> new Control(buf.readUtf(32), buf.readUtf(64), buf.readVarInt(), buf.readVarInt(), buf.readFloat()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
