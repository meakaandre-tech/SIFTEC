package com.meakaandre.siftec.power;

import com.zurrtum.create.content.kinetics.KineticNetwork;
import com.zurrtum.create.content.kinetics.base.GeneratingKineticBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * Power Storage. Once a second it looks at its kinetic network:
 * - spare capacity on the line is taken as charge (it shows up as stress, like any machine);
 * - if the line is overstressed, or nothing is driving it at all, the storage becomes a source and
 *   covers the shortfall until it is empty or the line can carry itself again.
 * Energy is counted in SU-seconds.
 */
public class StorageBlockEntity extends GeneratingKineticBlockEntity {
    public static final float CAPACITY = 1_024_000f, MAX_CHARGE = 8192f, MAX_DISCHARGE = 4096f;
    /** The speed it runs a line at when nothing else is turning it. */
    private static final float OWN_SPEED = 32f;
    public static final int IDLE = 0, CHARGING = 1, DISCHARGING = 2;

    public float stored;
    public int mode = IDLE;
    private float chargeRate;
    private float runSpeed = OWN_SPEED;
    private int clock;

    public StorageBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public float getGeneratedSpeed() {
        return mode == DISCHARGING ? runSpeed : 0;
    }

    @Override
    public float calculateAddedStressCapacity() {
        float capacity = mode == DISCHARGING && runSpeed != 0 ? MAX_DISCHARGE / Math.abs(runSpeed) : 0;
        lastCapacityProvided = capacity;
        return capacity;
    }

    @Override
    public float calculateStressApplied() {
        float speed = Math.abs(getTheoreticalSpeed());
        float impact = mode == CHARGING && speed != 0 ? chargeRate / speed : 0;
        lastStressApplied = impact;
        return impact;
    }

    @Override
    public void tick() {
        super.tick();
        if (level == null || level.isClientSide() || ++clock < 20) return;
        clock = 0;
        int before = mode;
        float speed = getTheoreticalSpeed();
        KineticNetwork network = hasNetwork() ? getOrCreateNetwork() : null;
        float capacity = network == null ? 0 : network.calculateCapacity();
        float stress = network == null ? 0 : network.calculateStress();

        if (mode == DISCHARGING) {
            float others = capacity - MAX_DISCHARGE;
            stored -= Math.clamp(stress - others, 0, MAX_DISCHARGE);
            if (stored <= 0) {
                stored = 0;
                mode = IDLE;
            } else if (others > 0 && others >= stress) {
                mode = IDLE;   // the line can carry itself again
            }
        } else if (speed == 0 || network == null) {
            chargeRate = 0;
            mode = stored > 0 ? DISCHARGING : IDLE;
            runSpeed = OWN_SPEED;
        } else {
            float own = mode == CHARGING ? chargeRate : 0;
            float spare = capacity - (stress - own);
            if (spare < 0 && stored > 0) {
                // overstressed: join in at the line's own speed and direction
                chargeRate = 0;
                runSpeed = speed;
                mode = DISCHARGING;
            } else {
                stored = Math.min(CAPACITY, stored + own);
                float rate = Math.clamp(spare * 0.9f, 0, Math.min(MAX_CHARGE, CAPACITY - stored));
                chargeRate = rate < 1 ? 0 : rate;
                mode = chargeRate > 0 ? CHARGING : IDLE;
                network.updateStressFor(this, calculateStressApplied());
            }
        }
        if (mode != before) {
            updateGeneratedRotation();
            if (hasNetwork()) getOrCreateNetwork().updateStressFor(this, calculateStressApplied());
        }
        setChanged();
    }

    @Override
    protected void write(ValueOutput view, boolean clientPacket) {
        super.write(view, clientPacket);
        view.putFloat("Stored", stored);
        view.putInt("Mode", mode);
        view.putFloat("ChargeRate", chargeRate);
        view.putFloat("RunSpeed", runSpeed);
    }

    @Override
    protected void read(ValueInput view, boolean clientPacket) {
        super.read(view, clientPacket);
        stored = view.getFloatOr("Stored", 0);
        mode = view.getIntOr("Mode", IDLE);
        chargeRate = view.getFloatOr("ChargeRate", 0);
        runSpeed = view.getFloatOr("RunSpeed", OWN_SPEED);
    }
}
