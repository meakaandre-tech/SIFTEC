package com.meakaandre.siftec.building;

import com.zurrtum.create.infrastructure.fluids.FluidStack;

/**
 * Diesel Generators' engine inside the Fuel Generator (mixin {@code DieselEngineMixin}, only when Diesel Generators is
 * there): the speed and SU it gives and the mB a tick it burns at full load, set by the building for the fuel in it
 * (Satisfactory's numbers), and the share of the generator's power the grid draws, so its fuel burns only for that. A
 * negative value leaves Diesel Generators' own.
 */
public interface EngineControl {
    void siftec$set(float speed, float strength, float burnPerTick, float load);

    FluidStack siftec$fuel();

    float siftec$burnPerTick();
}
