package com.meakaandre.siftec.map;

import net.minecraft.network.FriendlyByteBuf;

/**
 * A marker placed on the map, as Satisfactory's stamps and beacons: an icon, a colour and a name, private to the
 * player who placed it or shared with their company. A death marker is placed by the game where its player died
 * and is always private.
 */
public final class MapMarker {
    /** The icons a marker can have (MapIcons draws them in this order). */
    public static final int ICONS = 10;
    /** The colours a marker can have. */
    public static final int[] COLOURS = {0xF2A33A, 0xE5533D, 0xF2D64B, 0x6BCB5A, 0x4FB8E8, 0x3F6FE0, 0xB06BE0, 0xF0F0F0};
    public static final int MAX_NAME = 32;
    /** Longest marker id read from the network. */
    private static final int MAX_ID = 64;
    /** Longest dimension id read from the network. */
    private static final int MAX_DIMENSION = 128;
    /** Longest owner (player uuid) read from the network. */
    private static final int MAX_OWNER = 64;
    /** Bytes per character at most in a UTF-8 name. */
    private static final int UTF8_BYTES_PER_CHAR = 4;

    public String id = "";
    public String dimension = "minecraft:overworld";
    public int x, y, z;
    public String name = "";
    public int icon;
    public int colour;
    /** Shared with the company; false for private. */
    public boolean company;
    /** The player who placed it (uuid). */
    public String owner = "";
    /** The owner's company when placed (for company markers). */
    public String companyId = "";
    public boolean death;
    /**
     * A death marker that shows a Death Crate (death/DeathCrates.java): kept until the crate is emptied or taken away,
     * whatever the number of deaths since. Only the server needs it, so it is not sent.
     */
    public boolean crate;
    /** Real time (ms) it was placed. */
    public long at;

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(id);
        buf.writeUtf(dimension);
        buf.writeVarInt(x);
        buf.writeVarInt(y);
        buf.writeVarInt(z);
        buf.writeUtf(name);
        buf.writeVarInt(icon);
        buf.writeVarInt(colour);
        buf.writeBoolean(company);
        buf.writeUtf(owner);
        buf.writeBoolean(death);
        buf.writeLong(at);
    }

    public static MapMarker read(FriendlyByteBuf buf) {
        MapMarker m = new MapMarker();
        m.id = buf.readUtf(MAX_ID);
        m.dimension = buf.readUtf(MAX_DIMENSION);
        m.x = buf.readVarInt();
        m.y = buf.readVarInt();
        m.z = buf.readVarInt();
        m.name = buf.readUtf(MAX_NAME * UTF8_BYTES_PER_CHAR);
        m.icon = buf.readVarInt();
        m.colour = buf.readVarInt();
        m.company = buf.readBoolean();
        m.owner = buf.readUtf(MAX_OWNER);
        m.death = buf.readBoolean();
        m.at = buf.readLong();
        return m;
    }

    public String pinKey() {
        return "marker:" + id;
    }

    /** Keeps icon, colour and name inside what the map can show. */
    public void clamp() {
        icon = Math.floorMod(icon, ICONS);
        colour = Math.floorMod(colour, COLOURS.length);
        name = name.strip();
        if (name.length() > MAX_NAME) name = name.substring(0, MAX_NAME);
    }
}
