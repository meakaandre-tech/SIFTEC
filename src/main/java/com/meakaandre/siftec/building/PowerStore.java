package com.meakaandre.siftec.building;

import com.meakaandre.siftec.power.Power;

/** Power Storage in its grid: holds energy, charged at up to its charge rate. */
final class PowerStore extends FuseHolder {
    PowerStore(ControllerBlockEntity c) {
        super(c);
    }

    @Override
    public double stored() {
        return c.energy;
    }

    @Override
    public double storeCapacity() {
        return c.isRemoved() ? 0 : Power.su(BuildingPower.STORAGE_MWH) * Power.TICKS_PER_HOUR;
    }

    @Override
    public double chargeRate() {
        return c.tripped ? 0 : Power.su(BuildingPower.STORAGE_CHARGE_MW);
    }

    @Override
    public void store(double suTicks) {
        c.energy = Math.clamp(c.energy + suTicks, 0, storeCapacity());
        c.lastGenerated = -suTicks;
        c.setChanged();
    }
}
