package com.meakaandre.siftec.geyser;

import com.meakaandre.siftec.fluid.FluidEntry;
import com.meakaandre.siftec.fluid.ModFluids;
import com.zurrtum.create.api.behaviour.BlockEntityBehaviour;
import com.zurrtum.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.zurrtum.create.foundation.blockEntity.behaviour.fluid.SmartFluidTankBehaviour;
import com.zurrtum.create.infrastructure.fluids.FluidStack;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import org.jspecify.annotations.Nullable;
import net.minecraft.world.level.block.PotentSulfurBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.PotentSulfurState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.List;

/**
 * Sits over a geyser (vanilla potent sulfur over magma, under water) with at least one block of air between the
 * water and the engine, so the geyser's gas still escapes and its own clock keeps running. Each eruption drives
 * the engine hard for its few seconds, then it is quiet until the next one.
 */
public class GeyserEngineBlockEntity extends GeneratingKineticBlockEntity {
    private static final int MB = 81;
    /** While the geyser erupts: this speed, and this much stress capacity in total. */
    public static final float SPEED = 64f, BURST_SU = 32768f;
    /** Acid made per second of eruption, and how much the engine holds before it has to stop. */
    public static final int ACID_PER_SECOND = 50, TANK = 4000;
    /** How far down the vent may be: a gap of air, then the geyser's water (vanilla allows up to four blocks of it). */
    public static final int REACH = 8;
    /** No eruption is longer than this (vanilla's longest is five seconds, under four blocks of water). */
    public static final int MAX_ERUPTION_TICKS = 6 * 20;

    public SmartFluidTankBehaviour tank;
    private boolean running;
    /** Set once the geyser has been seen resting; an eruption only counts after that, and only up to MAX_ERUPTION_TICKS. */
    private boolean armed;
    private int eruptTicks;
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

    public boolean running() {
        return running;
    }

    /** What the engine sees, for the selftest. */
    public String describe() {
        BlockPos vent = level == null ? null : findVent(level, worldPosition);
        return "vent " + (vent == null ? "none" : vent.toShortString() + " gas escapes " + gasEscapes(level, vent) + " state "
            + level.getBlockState(vent).getValue(PotentSulfurBlock.STATE).getSerializedName()) + ", armed " + armed + ", erupt ticks " + eruptTicks
            + ", running " + running + ", speed " + getSpeed() + ", acid " + acidMb();
    }

    public String statusKey() {
        if (acidMb() >= TANK - ACID_PER_SECOND) return "siftec.geyser.full";
        if (running) return "siftec.geyser.running";
        BlockPos vent = level == null ? null : findVent(level, worldPosition);
        if (vent == null) return "siftec.geyser.none";
        return gasEscapes(level, vent) ? "siftec.geyser.waiting" : "siftec.geyser.blocked";
    }

    /**
     * The potent sulfur block under this position, looking down through air and then the geyser's water; null
     * if there is none in reach or something solid is in the way.
     */
    public static @Nullable BlockPos findVent(Level level, BlockPos engine) {
        boolean water = false;
        for (int i = 1; i <= REACH; i++) {
            BlockPos at = engine.below(i);
            BlockState state = level.getBlockState(at);
            if (state.is(Blocks.POTENT_SULFUR)) return water ? at : null;
            if (level.getFluidState(at).isSourceOfType(Fluids.WATER)) {
                water = true;
                continue;
            }
            // air (or anything gas passes) above the water; once in the water, only water
            if (water || !passable(level, at, state)) return null;
        }
        return null;
    }

    private static boolean passable(Level level, BlockPos pos, BlockState state) {
        return state.isAir() || state.getCollisionShape(level, pos).isEmpty() && state.getFluidState().isEmpty();
    }

    /**
     * True if the vent's gas reaches open air: the way vanilla's geyser looks for it (up through at most four
     * water blocks, then a free block). When it does not, the geyser's own clock stands still.
     */
    public static boolean gasEscapes(Level level, BlockPos vent) {
        for (int y = 1; y <= 5; y++) {
            BlockPos at = vent.above(y);
            BlockState state = level.getBlockState(at);
            if (level.getFluidState(at).isSourceOfType(Fluids.WATER) && (state.is(Blocks.WATER) || passable(level, at, state))) continue;
            return passable(level, at, state);
        }
        return false;
    }

    /** True if an engine at this position would sit right on the geyser's water and shut its gas in. */
    public static boolean blocksVent(Level level, BlockPos engine) {
        if (!level.getFluidState(engine.below()).isSourceOfType(Fluids.WATER)) return false;
        for (int i = 1; i <= REACH; i++) {
            BlockPos at = engine.below(i);
            if (level.getBlockState(at).is(Blocks.POTENT_SULFUR)) return true;
            if (!level.getFluidState(at).isSourceOfType(Fluids.WATER)) return false;
        }
        return false;
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
        BlockPos vent = findVent(level, worldPosition);
        PotentSulfurState state = vent != null && gasEscapes(level, vent) ? level.getBlockState(vent).getValue(PotentSulfurBlock.STATE) : null;
        boolean erupting = state == PotentSulfurState.ERUPTING;
        if (state == PotentSulfurState.DORMANT) {
            armed = true;
            eruptTicks = 0;
        } else if (!erupting) {
            // no geyser, or it cannot breathe: wait until it is seen resting again
            armed = false;
            eruptTicks = 0;
        }
        if (erupting && armed && ++eruptTicks >= MAX_ERUPTION_TICKS) armed = false;
        boolean full = acidMb() >= TANK - ACID_PER_SECOND;
        boolean now = erupting && armed && !full;
        if (now != running) {
            running = now;
            clock = 0;
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
        view.putBoolean("Armed", armed);
        view.putInt("EruptTicks", eruptTicks);
    }

    @Override
    protected void read(ValueInput view, boolean clientPacket) {
        super.read(view, clientPacket);
        running = view.getBooleanOr("Running", false);
        armed = view.getBooleanOr("Armed", false);
        eruptTicks = view.getIntOr("EruptTicks", 0);
    }
}
