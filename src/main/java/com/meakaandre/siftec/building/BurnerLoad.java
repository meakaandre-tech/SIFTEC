package com.meakaandre.siftec.building;

/**
 * A Blaze Burner under a generator's boiler (mixin {@code BurnerLoadMixin}): the share of its generator's power the grid
 * draws, 0 to 1, so its fuel burns down only as fast as that (Satisfactory's generators burn only for what is used).
 * Negative (the default) is Create's own one tick a tick. The building sets it every tick.
 */
public interface BurnerLoad {
    void siftec$load(float load);

    float siftec$load();
}
