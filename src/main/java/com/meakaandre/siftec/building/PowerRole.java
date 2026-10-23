package com.meakaandre.siftec.building;

import com.google.gson.annotations.SerializedName;

/** A building's part in its power grid ({@link BuildingDef#power()}, siftec_content.json "power"); NONE for a plain consumer or nothing. */
public enum PowerRole {
    @SerializedName("") NONE(""),
    @SerializedName("pole") POLE("pole"),
    @SerializedName("storage") STORAGE("storage"),
    @SerializedName("biomass") BIOMASS("biomass"),
    @SerializedName("coal") COAL("coal"),
    @SerializedName("fuel") FUEL("fuel"),
    @SerializedName("nuclear") NUCLEAR("nuclear"),
    @SerializedName("geothermal") GEOTHERMAL("geothermal"),
    @SerializedName("augmenter") AUGMENTER("augmenter");

    private final String id;

    PowerRole(String id) {
        this.id = id;
    }

    /** Its id, as the data and the save write it. */
    public String id() {
        return id;
    }

    public static PowerRole of(String id) {
        for (PowerRole v : values()) if (v.id.equals(id)) return v;
        throw new IllegalArgumentException("unknown power role \"" + id + "\"");
    }

    /** The generators that burn fuel (items or a fluid), so their machines bank energy. */
    public boolean burnsFuel() {
        return this == BIOMASS || this == COAL || this == FUEL || this == NUCLEAR;
    }

    /** The members that give power without fuel: the Geothermal Generator and the Alien Power Augmenter. */
    public boolean unfuelled() {
        return this == GEOTHERMAL || this == AUGMENTER;
    }

    /** The members that give power to their grid: the generators (biomass, coal, fuel, nuclear, geothermal) and the Augmenter. */
    public boolean givesPower() {
        return this == BIOMASS || this == COAL || this == FUEL || this == NUCLEAR || this == GEOTHERMAL || this == AUGMENTER;
    }

    /** The members that hold their grid's fuse: generators, the Augmenter and Power Storage. */
    public boolean holdsFuse() {
        return givesPower() || this == STORAGE;
    }

    @Override
    public String toString() {
        return id;
    }
}
