package com.meakaandre.siftec.building;

import com.meakaandre.siftec.power.GridStats;
import com.meakaandre.siftec.power.PowerMember;

/** A building that holds its grid's fuse (generators, the Augmenter, Power Storage): trips and resets with it. */
abstract class FuseHolder implements PowerMember {
    final ControllerBlockEntity c;

    FuseHolder(ControllerBlockEntity c) {
        this.c = c;
    }

    @Override
    public boolean holdsFuse() {
        return true;
    }

    @Override
    public boolean gone() {
        return c.isRemoved();
    }

    @Override
    public boolean tripped() {
        return c.tripped;
    }

    @Override
    public void trip(boolean tripped) {
        if (c.tripped != tripped) {
            c.tripped = tripped;
            c.setChanged();
        }
    }

    @Override
    public void grid(GridStats stats) {
        c.grid = stats;
    }
}
