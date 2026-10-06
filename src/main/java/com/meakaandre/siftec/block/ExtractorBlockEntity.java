package com.meakaandre.siftec.block;

import com.meakaandre.siftec.fluid.FluidEntry;
import com.meakaandre.siftec.fluid.ModFluids;
import com.meakaandre.siftec.node.Node;
import com.meakaandre.siftec.node.NodeType;
import com.zurrtum.create.api.behaviour.BlockEntityBehaviour;
import com.zurrtum.create.content.kinetics.base.KineticBlockEntity;
import com.zurrtum.create.foundation.blockEntity.behaviour.fluid.SmartFluidTankBehaviour;
import com.zurrtum.create.infrastructure.fluids.FluidStack;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.List;
import java.util.Optional;

public class ExtractorBlockEntity extends KineticBlockEntity {
    /** Fluid is counted in droplets: 81 to the millibucket. */
    private static final int MB = 81;
    /** Rotation needed per 100 mB on a normal node: 150 mB a second at 32 RPM. */
    private static final int CYCLE = 426;

    public SmartFluidTankBehaviour tank;
    private float progress;
    private Optional<Node> node = Optional.empty();
    private int recheck;

    public ExtractorBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour<?>> behaviours) {
        super.addBehaviours(behaviours);
        tank = SmartFluidTankBehaviour.single(this, 4000 * MB).forbidInsertion();
        behaviours.add(tank);
    }

    @Override
    public void tick() {
        super.tick();
        if (level == null || level.isClientSide()) return;
        if (recheck-- <= 0) {
            recheck = 40;
            node = Mining.nodeUnder(level, worldPosition).filter(n -> n.type() == NodeType.NITROGEN);
        }
        float speed = Math.abs(getSpeed());
        FluidEntry nitrogen = ModFluids.ALL.get("nitrogen");
        if (speed == 0 || node.isEmpty() || nitrogen == null) return;
        progress += speed * node.get().purity().multiplier;
        while (progress >= CYCLE) {
            progress -= CYCLE;
            if (tank.getPrimaryHandler().insert(new FluidStack(nitrogen.still, 100 * MB)) == 0) {
                progress = 0;
                break;
            }
        }
    }

    @Override
    public float calculateStressApplied() {
        lastStressApplied = 8f;
        return lastStressApplied;
    }

    @Override
    protected void write(ValueOutput view, boolean clientPacket) {
        super.write(view, clientPacket);
        view.putFloat("Progress", progress);
    }

    @Override
    protected void read(ValueInput view, boolean clientPacket) {
        super.read(view, clientPacket);
        progress = view.getFloatOr("Progress", 0);
    }
}
