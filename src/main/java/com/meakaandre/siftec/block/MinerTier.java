package com.meakaandre.siftec.block;

/** Miner marks. A higher mark gets more out of the same rotation. */
public enum MinerTier {
    MK1(1280, 4f);

    /**
     * Rotation needed per item on a normal node: every tick adds the shaft's RPM.
     * 1280 means 30 items a minute at 32 RPM and 240 a minute at 256 RPM.
     */
    public final int cycle;
    /** Stress impact, in SU per RPM. */
    public final float stress;

    MinerTier(int cycle, float stress) {
        this.cycle = cycle;
        this.stress = stress;
    }
}
