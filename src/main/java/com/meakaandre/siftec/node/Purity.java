package com.meakaandre.siftec.node;

/** Node purity, as in Satisfactory: it multiplies a miner's output. */
public enum Purity {
    IMPURE(0.5f), NORMAL(1f), PURE(2f);

    public final float multiplier;

    Purity(float multiplier) {
        this.multiplier = multiplier;
    }

    public String key() {
        return "siftec.purity." + name().toLowerCase(java.util.Locale.ROOT);
    }
}
