package com.meakaandre.siftec.map;

import net.minecraft.network.FriendlyByteBuf;

import java.util.Locale;

/**
 * Something the map shows that the player did not place: a resource node or well, a geyser, a crash site, an
 * artifact or one of the company's key buildings.
 *
 * @param kind   what it is
 * @param sub    for nodes and wells the node type's id; empty otherwise
 * @param purity for nodes and wells the purity's ordinal (0 impure, 1 normal, 2 pure); 0 otherwise
 */
public record MapPoi(Kind kind, String sub, int purity, int x, int y, int z) {
    /** Longest sub-kind read from the network. */
    private static final int MAX_SUB = 32;

    /** The filter panel's categories, in Satisfactory's order (no vehicles). */
    public enum Category {
        PLAYERS, MARKERS, NODES, WELLS, GEYSERS, CRASH_SITES, ARTIFACTS, BUILDINGS;

        public String key() {
            return "siftec.map.filter." + name().toLowerCase(Locale.ROOT);
        }
    }

    public enum Kind {
        NODE(Category.NODES), WELL(Category.WELLS), GEYSER(Category.GEYSERS), CRASH_SITE(Category.CRASH_SITES),
        MERCER_SPHERE(Category.ARTIFACTS), SOMERSLOOP(Category.ARTIFACTS),
        HUB(Category.BUILDINGS), GATEWAY(Category.BUILDINGS), RADAR_TOWER(Category.BUILDINGS), TRAIN_STATION(Category.BUILDINGS),
        TRUCK_STATION(Category.BUILDINGS), DRONE_PORT(Category.BUILDINGS);

        public final Category category;

        Kind(Category category) {
            this.category = category;
        }

        public String key() {
            return "siftec.map.poi." + name().toLowerCase(Locale.ROOT);
        }
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeByte(kind.ordinal());
        buf.writeUtf(sub);
        buf.writeByte(purity);
        buf.writeVarInt(x);
        buf.writeVarInt(y);
        buf.writeVarInt(z);
    }

    public static MapPoi read(FriendlyByteBuf buf) {
        Kind[] kinds = Kind.values();
        Kind kind = kinds[Math.floorMod(buf.readByte(), kinds.length)];
        return new MapPoi(kind, buf.readUtf(MAX_SUB), buf.readByte(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
    }

    /** What identifies it for pins: the same thing at the same place. */
    public String pinKey() {
        return "poi:" + kind.name() + ":" + sub + ":" + x + ":" + z;
    }
}
