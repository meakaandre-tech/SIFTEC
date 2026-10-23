package com.meakaandre.siftec.sim;

import com.google.gson.annotations.SerializedName;

/** What a simulation node stands for ({@link SimData.Node#kind}): a Build Gun building, a plain inventory a belt ends in, or a Drone Port. */
public enum NodeKind {
    @SerializedName("building") BUILDING("building"),
    @SerializedName("inventory") INVENTORY("inventory"),
    @SerializedName("drone_port") DRONE_PORT("drone_port");

    private final String id;

    NodeKind(String id) {
        this.id = id;
    }

    /** Its id, as the data and the save write it. */
    public String id() {
        return id;
    }

    public static NodeKind of(String id) {
        for (NodeKind v : values()) if (v.id.equals(id)) return v;
        throw new IllegalArgumentException("unknown node kind \"" + id + "\"");
    }

    @Override
    public String toString() {
        return id;
    }
}
