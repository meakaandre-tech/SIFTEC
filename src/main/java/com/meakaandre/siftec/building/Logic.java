package com.meakaandre.siftec.building;

import com.google.gson.annotations.SerializedName;

/** How a building works ({@link BuildingDef#logic()}, siftec_content.json "logic"): which of {@link BuildingLogic}'s jobs its controller runs. */
public enum Logic {
    @SerializedName("") NONE(""),
    @SerializedName("power") POWER("power"),
    @SerializedName("foundation") FOUNDATION("foundation"),
    @SerializedName("basin") BASIN("basin"),
    @SerializedName("miner") MINER("miner"),
    @SerializedName("designer") DESIGNER("designer"),
    @SerializedName("storage") STORAGE("storage"),
    @SerializedName("assembly") ASSEMBLY("assembly"),
    @SerializedName("extractor") EXTRACTOR("extractor"),
    @SerializedName("processor") PROCESSOR("processor"),
    @SerializedName("portable_miner") PORTABLE_MINER("portable_miner"),
    @SerializedName("smelter") SMELTER("smelter"),
    @SerializedName("packager") PACKAGER("packager"),
    @SerializedName("pressurizer") PRESSURIZER("pressurizer"),
    @SerializedName("well_extractor") WELL_EXTRACTOR("well_extractor"),
    @SerializedName("workshop") WORKSHOP("workshop"),
    @SerializedName("sink") SINK("sink"),
    @SerializedName("shop") SHOP("shop"),
    @SerializedName("radar") RADAR("radar"),
    @SerializedName("truck_station") TRUCK_STATION("truck_station"),
    @SerializedName("drone_port") DRONE_PORT("drone_port");

    private final String id;

    Logic(String id) {
        this.id = id;
    }

    /** Its id, as the data and the save write it. */
    public String id() {
        return id;
    }

    public static Logic of(String id) {
        for (Logic v : values()) if (v.id.equals(id)) return v;
        throw new IllegalArgumentException("unknown building logic \"" + id + "\"");
    }

    /** The buildings whose controller takes a recipe filter: what their Basin, line or recipe makes. */
    public boolean hasFilter() {
        return this == BASIN || this == ASSEMBLY || this == PROCESSOR;
    }

    /** The Foundations-tab buildings and the Blueprint Designer: built of foundation blocks, snapped to the grid. */
    public boolean isFoundation() {
        return this == FOUNDATION || this == DESIGNER;
    }

    /** Whether its status counts what it has made (recipes run, breaks, items sunk). */
    public boolean countsMade() {
        return switch (this) {
            case BASIN, MINER, ASSEMBLY, PROCESSOR, PORTABLE_MINER, SMELTER, PACKAGER, SINK -> true;
            case NONE, POWER, FOUNDATION, DESIGNER, STORAGE, EXTRACTOR, PRESSURIZER, WELL_EXTRACTOR, WORKSHOP, SHOP, RADAR,
                 TRUCK_STATION, DRONE_PORT -> false;
        };
    }

    @Override
    public String toString() {
        return id;
    }
}
