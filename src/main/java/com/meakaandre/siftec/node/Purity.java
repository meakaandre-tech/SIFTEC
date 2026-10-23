package com.meakaandre.siftec.node;

import net.minecraft.util.StringRepresentable;

import java.util.Locale;

/** Node purity, as in Satisfactory: it multiplies a miner's output. */
public enum Purity implements StringRepresentable {
    IMPURE(0.5f), NORMAL(1f), PURE(2f);

    public final float multiplier;

    Purity(float multiplier) {
        this.multiplier = multiplier;
    }

    @Override
    public String getSerializedName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Purity byName(String name) {
        for (Purity p : values()) if (p.getSerializedName().equals(name)) return p;
        return null;
    }

    public String key() {
        return "siftec.purity." + name().toLowerCase(Locale.ROOT);
    }
}
