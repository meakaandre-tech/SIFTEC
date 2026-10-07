package com.meakaandre.siftec.governor;

import com.meakaandre.siftec.tweak.SpeedCap;
import com.zurrtum.create.content.kinetics.RotationPropagator;
import com.zurrtum.create.content.kinetics.transmission.SplitShaftBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * Works like a Gearshift whose ratio is worked out from the speed coming in: the end the rotation arrives at
 * turns with the network, the other end turns at the chosen speed. Stress follows speed as usual, so turning
 * a network up costs as much as gearing it up would.
 */
public class GovernorBlockEntity extends SplitShaftBlockEntity {
    private int target = SpeedCap.START;

    public GovernorBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public int target() {
        return target;
    }

    /** The speed limit of the company this governor belongs to. */
    public int limit() {
        return SpeedCap.of(this);
    }

    @Override
    public float getRotationSpeedModifier(Direction face) {
        if (!hasSource() || face == getSourceFacing()) return 1;
        float in = Math.abs(getTheoreticalSpeed());
        return in == 0 ? 1 : target / in;
    }

    /**
     * The exact speed passed across the far end, given what Create worked out. Dividing and multiplying back
     * does not always land on the same number, and Create reads the difference as one side overpowering the other.
     */
    public float exact(com.zurrtum.create.content.kinetics.base.KineticBlockEntity from, com.zurrtum.create.content.kinetics.base.KineticBlockEntity to, float worked) {
        if (worked == 0 || !hasSource()) return worked;
        float in = getTheoreticalSpeed();
        BlockPos other = (from == this ? to : from).getBlockPos();
        if (in == 0 || other.equals(source)) return worked;
        if (from == this) return Math.copySign(target, worked);
        return Math.abs(from.getTheoreticalSpeed()) == target ? Math.copySign(Math.abs(in), worked) : worked;
    }

    public void setTarget(int rpm) {
        rpm = Math.clamp(rpm, 1, limit());
        if (rpm == target || level == null || level.isClientSide()) return;
        target = rpm;
        // the same steps Create's Rotation Speed Controller takes: leave the network, then join it again
        if (hasNetwork()) getOrCreateNetwork().remove(this);
        RotationPropagator.handleRemoved(level, worldPosition, this);
        removeSource();
        attachKinetics();
        notifyUpdate();
    }

    @Override
    protected void write(ValueOutput view, boolean clientPacket) {
        super.write(view, clientPacket);
        view.putInt("Target", target);
    }

    @Override
    protected void read(ValueInput view, boolean clientPacket) {
        super.read(view, clientPacket);
        target = view.getIntOr("Target", SpeedCap.START);
    }
}
