package com.meakaandre.siftec.building;

/**
 * A Create boiler inside a generator building ({@code BoilerData}, mixin {@code BoilerWaterMixin}): how much water a
 * heat level takes, in droplets a tick, so the boiler drinks Satisfactory's water. 0 is a closed loop that needs none
 * (the Biomass Burner's), a negative value Create's own 810. The building sets it every tick.
 */
public interface BoilerWater {
    void siftec$waterPerLevel(float droplets);

    float siftec$waterPerLevel();
}
