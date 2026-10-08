package com.meakaandre.siftec.node;

/** Node purity, as in Satisfactory: it multiplies a miner's output. */
public enum Purity implements net.minecraft.util.StringRepresentable {
    IMPURE(0.5f), NORMAL(1f), PURE(2f);

    public final float multiplier;

    Purity(float multiplier) {
        this.multiplier = multiplier;
    }

    @Override
    public String getSerializedName() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    public String key() {
        return "siftec.purity." + name().toLowerCase(java.util.Locale.ROOT);
    }
}
