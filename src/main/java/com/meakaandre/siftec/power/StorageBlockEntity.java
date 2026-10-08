package com.meakaandre.siftec.power;

import com.zurrtum.create.content.kinetics.base.GeneratingKineticBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * Power Storage. Every tick it looks at its kinetic network:
 * - spare capacity on the line is taken as charge (it shows up as stress, like any machine), and banked tick by
 *   tick, so even a geyser's eruption of a second or two is caught;
 * - if the line is overstressed, or nothing is driving it at all, the storage becomes a source and covers the
 *   shortfall until it is empty or the line can carry itself again. It only spends what the line actually
 *   takes, and nothing while the line cannot turn anyway.
 * It turns a line the way that line last turned (or the way it was turned when it charged), so it never meets
 * another source head on. Energy is counted in SU-seconds.
 */
public class StorageBlockEntity extends GeneratingKineticBlockEntity {
    public static final float CAPACITY = 1_024_000f, MAX_CHARGE = 8192f, MAX_DISCHARGE = 4096f;
    /** The speed it runs a line at when nothing else is turning it. */
    private static final float OWN_SPEED = 32f;
    /** After giving up on a line that stays overstressed even with its help, it waits this long before trying again. */
    private static final int RETRY_TICKS = 100;
    public static final int IDLE = 0, CHARGING = 1, DISCHARGING = 2;

    public float stored;
    public int mode = IDLE;
    private float chargeRate;
    /** Signed: the direction is the one the line last turned. */
    private float runSpeed = OWN_SPEED;
    private int clock, cooldown;

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
        if (level == null || level.isClientSide()) return;
        clock++;
        if (cooldown > 0) cooldown--;
        int before = mode;
        float speed = getTheoreticalSpeed();
        boolean networked = hasNetwork();

        if (mode == DISCHARGING) {
            // capacity and stress are the network's totals, kept up to date by Create
            float others = capacity - MAX_DISCHARGE;
            if (!networked || isOverStressed()) {
                // even with this storage the line cannot turn: nothing would be delivered, so stop and spend nothing
                mode = IDLE;
                cooldown = RETRY_TICKS;
            } else {
                float delivered = Math.clamp(stress - Math.max(0, others), 0, MAX_DISCHARGE);
                stored -= delivered / 20f;
                if (stored <= 0) {
                    stored = 0;
                    mode = IDLE;
                } else if (others > 0 && others >= stress) {
                    mode = IDLE;   // the line can carry itself again
                }
            }
        } else if (speed == 0 || !networked) {
            if (chargeRate != 0) {
                chargeRate = 0;
                mode = IDLE;
            }
            if (stored > 0 && cooldown == 0) {
                // nothing turns the line: turn it, the way it last turned
                runSpeed = Math.copySign(OWN_SPEED, runSpeed);
                mode = DISCHARGING;
            }
        } else {
            runSpeed = Math.copySign(OWN_SPEED, speed);
            float own = mode == CHARGING ? chargeRate : 0;
            // bank what the line gave this tick
            if (own > 0 && !isOverStressed()) stored = Math.min(CAPACITY, stored + own / 20f);
            float spare = capacity - (stress - own);
            if (spare < 0 && stored > 0 && cooldown == 0) {
                // overstressed: join in at the line's own speed and direction
                chargeRate = 0;
                runSpeed = speed;
                mode = DISCHARGING;
            } else if (mode == IDLE || clock % 4 == 0 || stored >= CAPACITY) {
                float rate = Math.clamp(spare * 0.9f, 0, Math.min(MAX_CHARGE, CAPACITY - stored));
                if (rate < 1) rate = 0;
                // the network is only told about real changes: each update is felt along the whole line
                if (rate == 0 ? chargeRate != 0 : Math.abs(rate - chargeRate) > Math.max(16f, chargeRate * 0.05f)) {
                    chargeRate = rate;
                    mode = rate > 0 ? CHARGING : IDLE;
                    getOrCreateNetwork().updateStressFor(this, calculateStressApplied());
                }
            }
        }
        if ((mode == DISCHARGING) != (before == DISCHARGING)) {
            if (mode != DISCHARGING) chargeRate = 0;
            updateGeneratedRotation();
            if (hasNetwork()) getOrCreateNetwork().updateStressFor(this, calculateStressApplied());
        }
        if (clock % 20 == 0 || mode != before) setChanged();
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
