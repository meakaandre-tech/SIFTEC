package com.meakaandre.siftec.geyser;

import com.meakaandre.siftec.fluid.FluidEntry;
import com.meakaandre.siftec.fluid.ModFluids;
import com.zurrtum.create.api.behaviour.BlockEntityBehaviour;
import com.zurrtum.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.zurrtum.create.foundation.blockEntity.behaviour.fluid.SmartFluidTankBehaviour;
import com.zurrtum.create.infrastructure.fluids.FluidStack;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PotentSulfurBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.PotentSulfurState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.List;

public class GeyserEngineBlockEntity extends GeneratingKineticBlockEntity {
    private static final int MB = 81;
    /** While the geyser erupts: this speed, and this much stress capacity in total. */
    public static final float SPEED = 64f, BURST_SU = 32768f;
    /** Acid made per second of eruption, and how much the engine holds before it has to stop. */
    public static final int ACID_PER_SECOND = 50, TANK = 4000;
    /** The geyser may be this many blocks below the engine, with only air or water between. */
    private static final int REACH = 4;

    public SmartFluidTankBehaviour tank;
    private boolean running;
    private int clock;

    public GeyserEngineBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour<?>> behaviours) {
        super.addBehaviours(behaviours);
        tank = SmartFluidTankBehaviour.single(this, TANK * MB).forbidInsertion();
        behaviours.add(tank);
    }

    public int acidMb() {
        return tank.getPrimaryHandler().getFluid().getAmount() / MB;
    }

    public String statusKey() {
        if (acidMb() >= TANK - ACID_PER_SECOND) return "siftec.geyser.full";
        return running ? "siftec.geyser.running" : geyserBelow() == null ? "siftec.geyser.none" : "siftec.geyser.waiting";
    }

    /** The potent sulfur block under the engine, or null if there is none in reach. */
    private BlockState geyserBelow() {
        if (level == null) return null;
        for (int i = 1; i <= REACH; i++) {
            BlockState state = level.getBlockState(worldPosition.below(i));
            if (state.is(Blocks.POTENT_SULFUR)) return state;
            if (!state.isAir() && state.getFluidState().isEmpty()) return null;
        }
        return null;
    }

    @Override
    public float getGeneratedSpeed() {
        return running ? SPEED : 0;
    }

    @Override
    public float calculateAddedStressCapacity() {
        float capacity = running ? BURST_SU / SPEED : 0;
        lastCapacityProvided = capacity;
        return capacity;
    }

    @Override
    public void tick() {
        super.tick();
        if (level == null || level.isClientSide()) return;
        BlockState geyser = geyserBelow();
        boolean erupting = geyser != null && geyser.getValue(PotentSulfurBlock.STATE) == PotentSulfurState.ERUPTING;
        boolean full = acidMb() >= TANK - ACID_PER_SECOND;
        boolean now = erupting && !full;
        if (now != running) {
            running = now;
            updateGeneratedRotation();
        }
        if (running && ++clock >= 20) {
            clock = 0;
            FluidEntry acid = ModFluids.ALL.get("sulfuric_acid");
            if (acid != null) tank.getPrimaryHandler().insert(new FluidStack(acid.still, ACID_PER_SECOND * MB));
        }
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
