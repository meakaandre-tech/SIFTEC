package com.meakaandre.siftec.tweak;

import com.zurrtum.create.infrastructure.fluids.FluidInventory;
import com.zurrtum.create.infrastructure.fluids.FluidStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluids;

/** Water boils away out of Create's tanks and fluid machines in the Nether. Carried over from the old Tweaker mod. */
public final class NetherWater {
    /** mB lost per tick: a full eight-bucket tank is gone in about four seconds. */
    private static final int PER_TICK = 100, DROPLETS = 81;

    private NetherWater() {
    }

    public static boolean hot(Level level) {
        return level != null && !level.isClientSide() && level.dimension() == Level.NETHER;
    }

    public static void boil(Level level, BlockPos pos, FluidInventory tank, FluidStack held) {
        if (!hot(level) || held == null || held.isEmpty() || held.getFluid() != Fluids.WATER) return;
        tank.extract(held, PER_TICK * DROPLETS);
        if (level.getGameTime() % 10 == 0 && level instanceof ServerLevel server) {
            server.sendParticles(ParticleTypes.CLOUD, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 3, 0.3, 0.1, 0.3, 0.02);
        }
    }
}
