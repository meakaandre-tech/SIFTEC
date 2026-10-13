package com.meakaandre.siftec.building;

import com.zurrtum.create.content.kinetics.base.GeneratingKineticBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * A generator building's turbine: it turns the generator coils beside it at 64 RPM while the generator gives power,
 * the way the steam engine or the engine of the real thing would. It is only the look: the power itself is the
 * generator's own ({@link BuildingPower}).
 */
public class TurbineBlockEntity extends GeneratingKineticBlockEntity {
    public static final float RPM = 64, SU = 16384;
    public boolean running;

    public TurbineBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public void run(boolean on) {
        if (running == on) return;
        running = on;
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
        return running ? RPM : 0;
    }

    @Override
    public float calculateAddedStressCapacity() {
        float perRpm = SU / RPM;
        lastCapacityProvided = perRpm;
        return perRpm;
    }

    @Override
    protected void write(ValueOutput view, boolean clientPacket) {
        super.write(view, clientPacket);
        view.putBoolean("Running", running);
    }

    @Override
    protected void read(ValueInput view, boolean clientPacket) {
        super.read(view, clientPacket);
        running = view.getBooleanOr("Running", false);
    }
}
