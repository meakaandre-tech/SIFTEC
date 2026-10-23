package com.meakaandre.siftec.sim;

import com.google.gson.annotations.SerializedName;

/** How the factory simulation runs a node ({@link SimData.Node#mode}); NONE: nothing to simulate. */
public enum NodeMode {
    @SerializedName("none") NONE("none"),
    @SerializedName("craft") CRAFT("craft"),
    @SerializedName("smelt") SMELT("smelt"),
    @SerializedName("mine") MINE("mine"),
    @SerializedName("extract") EXTRACT("extract"),
    @SerializedName("sink") SINK("sink"),
    @SerializedName("port") PORT("port"),
    @SerializedName("store") STORE("store"),
    @SerializedName("gen") GEN("gen"),
    @SerializedName("battery") BATTERY("battery");

    private final String id;

    NodeMode(String id) {
        this.id = id;
    }

    /** Its id, as the data and the save write it. */
    public String id() {
        return id;
    }

    public static NodeMode of(String id) {
        for (NodeMode v : values()) if (v.id.equals(id)) return v;
        throw new IllegalArgumentException("unknown node mode \"" + id + "\"");
    }

    @Override
    public String toString() {
        return id;
    }
}
