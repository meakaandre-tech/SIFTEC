package com.meakaandre.siftec.building;

import com.zurrtum.create.content.kinetics.base.GeneratingKineticBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * The Geothermal Generator's turbine, driven by the geyser under it: it turns the generator coils beside it at the speed
 * the geyser gives ({@link Generators#geyserMw}), and the coils make the power from that. It can bear the coils' whole
 * drag at any speed.
 */
public class TurbineBlockEntity extends GeneratingKineticBlockEntity {
    /** SU a turn it can bear: more than its coils' magnets and their own drag (480 + 2 x 24). */
    public static final float SU_PER_RPM = 600;
    /** Its speed goes in steps of a quarter RPM: this many to the RPM. */
    private static final int RPM_STEPS = 4;
    public float speed;

    public TurbineBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    /** Turns at this speed from now on (0 stops it). */
    public void drive(float rpm) {
        float next = Math.round(rpm * RPM_STEPS) / (float) RPM_STEPS;
        if (next == speed) return;
        speed = next;
        setChanged();
        updateGeneratedRotation();
    }

    @Override
    public void initialize() {
        super.initialize();
        if (!hasSource() || getGeneratedSpeed() > getTheoreticalSpeed()) updateGeneratedRotation();
    }

    @Override
    public float getGeneratedSpeed() {
        return speed;
    }

    @Override
    public float calculateAddedStressCapacity() {
        lastCapacityProvided = SU_PER_RPM;
        return SU_PER_RPM;
    }

    @Override
    protected void write(ValueOutput view, boolean clientPacket) {
        super.write(view, clientPacket);
        view.putFloat("Speed", speed);
    }

    @Override
    protected void read(ValueInput view, boolean clientPacket) {
        super.read(view, clientPacket);
        speed = view.getFloatOr("Speed", 0f);
    }
}
