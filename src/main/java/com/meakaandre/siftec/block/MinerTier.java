package com.meakaandre.siftec.block;

import com.meakaandre.siftec.fluid.FluidEntry;
import com.meakaandre.siftec.fluid.ModFluids;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

/**
 * Miner marks, built like the old node drills: every tick adds the shaft's RPM (at most 256) to the progress, and
 * every {@link #CYCLE} of progress is one cycle, which brings up purity x {@link #output} items and uses
 * {@link #FLUID_PER_CYCLE} mB of the mark's fluid. 64 RPM is one cycle every 2 seconds.
 */
public enum MinerTier {
    //  items per cycle on a normal node, SU per RPM, fluid it drills with
    MK1(1, 16f, "water", "Water"),
    MK2(2, 64f, "drilling_mud", "Drilling Mud"),
    MK3(4, 192f, "coolant", "Coolant");

    public static final int CYCLE = 2560;
    public static final int FLUID_PER_CYCLE = 25;
    /** The fluid tank holds this many mB. */
    public static final int TANK = 4000;

    /** Items per cycle on a normal node. */
    public final int output;
    /** Stress impact, in SU per RPM: Mk.1 at 64 RPM asks for 1,024 SU. */
    public final float stress;
    /** "water", or the id of one of the pack's own fluids. */
    public final String fluidId;
    public final String fluidName;

    MinerTier(int output, float stress, String fluidId, String fluidName) {
        this.output = output;
        this.stress = stress;
        this.fluidId = fluidId;
        this.fluidName = fluidName;
    }

    public Fluid fluid() {
        if (fluidId.equals("water")) return Fluids.WATER;
        FluidEntry entry = ModFluids.ALL.get(fluidId);
        return entry == null ? Fluids.WATER : entry.still;
    }
}
