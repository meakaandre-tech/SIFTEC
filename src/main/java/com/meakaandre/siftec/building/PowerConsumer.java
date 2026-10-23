package com.meakaandre.siftec.building;

import com.meakaandre.siftec.power.GridStats;
import com.meakaandre.siftec.power.Power;
import com.meakaandre.siftec.power.PowerMember;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.antarcticgardens.cna.content.motor.MotorBlockEntity;

import java.util.List;

/** A powered building: its New Age motors run on the grid. */
final class PowerConsumer implements PowerMember {
    final ServerLevel level;
    final ControllerBlockEntity c;
    final BuildingDef def;

    PowerConsumer(ServerLevel level, ControllerBlockEntity c, BuildingDef def) {
        this.level = level;
        this.c = c;
        this.def = def;
    }

    /**
     * Its SU while it works, and only {@link BuildingPower#STANDBY_SU} while it is idle (nothing to work on, or its
     * output full), as Satisfactory's machines draw power only while they run. The standby draw keeps it on its grid,
     * so it still knows whether it has power (a blown fuse stops it), at next to no cost.
     */
    @Override
    public double demand() {
        if (c.isRemoved() || c.broken) return 0;
        if (BuildingLogic.idle(level, c, def)) {
            c.demandSu = BuildingPower.STANDBY_SU;
            return BuildingPower.STANDBY_SU;
        }
        // the Particle Accelerator and the Converter draw Satisfactory's power for where they are in their recipe's cycle
        c.demandSu = def.logic() == Logic.PROCESSOR ? Processors.demand(level, c, def) : def.su();
        return c.demandSu;
    }

    @Override
    public boolean gone() {
        return c.isRemoved();
    }

    @Override
    public void supplied(boolean on) {
        c.powered = on;
        // idle, its motors get nothing and wind down until there is work again
        if (!on || c.idle) return;
        List<BlockPos> motors = BuildingPower.motors(c);
        if (motors.isEmpty()) return;
        // each motor gets its share of the building's SU as FE; a New Age motor fed less than its full need gives
        // SU in proportion, so it gives exactly this share
        long fe = Math.round(Power.feOfSu(c.demandSu / (double) motors.size()));
        for (BlockPos pos : motors) {
            if (level.getBlockEntity(pos) instanceof MotorBlockEntity motor) {
                var storage = motor.getEnergyStorage();
                storage.internalInsert(Math.min(fe, storage.getCapacity() - storage.getStoredEnergy()), false);
            }
        }
    }

    @Override
    public void grid(GridStats stats) {
        c.grid = stats;
    }
}
