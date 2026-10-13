package com.meakaandre.siftec.building;

import com.zurrtum.create.content.kinetics.base.GeneratingKineticBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * A building's motor: the speed its building set (signed along its axis, so a fan blows the right way) and the SU it
 * gives at that speed, which is what the building draws.
 */
public class MotorBlockEntity extends GeneratingKineticBlockEntity {
    public float rpm;
    public float su;

    public MotorBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    /** Sets the speed and SU (the building does it when it goes down) and starts turning. */
    public void configure(float rpm, float su) {
        this.rpm = rpm;
        this.su = su;
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
        return BuildingLogic.powered(this) ? rpm : 0;
    }

    @Override
    public float calculateAddedStressCapacity() {
        float perRpm = rpm == 0 ? 0 : su / Math.abs(rpm);
        lastCapacityProvided = perRpm;
        return perRpm;
    }

    @Override
    protected void write(ValueOutput view, boolean clientPacket) {
        super.write(view, clientPacket);
        view.putFloat("Rpm", rpm);
        view.putFloat("Su", su);
    }

    @Override
    protected void read(ValueInput view, boolean clientPacket) {
        super.read(view, clientPacket);
        rpm = view.getFloatOr("Rpm", 0);
        su = view.getFloatOr("Su", 0);
    }
}
