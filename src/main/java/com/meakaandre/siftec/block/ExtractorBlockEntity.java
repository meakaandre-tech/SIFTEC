package com.meakaandre.siftec.block;

import com.meakaandre.siftec.building.ExtractorLogic;
import com.meakaandre.siftec.fluid.FluidEntry;
import com.meakaandre.siftec.fluid.ModFluids;
import com.meakaandre.siftec.node.Node;
import com.meakaandre.siftec.node.NodeType;
import com.meakaandre.siftec.owner.Ownership;
import com.zurrtum.create.api.behaviour.BlockEntityBehaviour;
import com.zurrtum.create.content.kinetics.base.KineticBlockEntity;
import com.zurrtum.create.foundation.blockEntity.behaviour.fluid.SmartFluidTankBehaviour;
import com.zurrtum.create.infrastructure.fluids.FluidStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.List;
import java.util.Optional;

/**
 * The placeable Resource Well Extractor block: on a nitrogen node, turned by a shaft on top, it fills its tank. Like the
 * Build Gun's Resource Well Extractor it needs a running Resource Well Pressurizer of its company within
 * {@link ExtractorLogic#WELL_RANGE} blocks, and counts toward that Pressurizer's
 * {@link ExtractorLogic#WELL_EXTRACTORS}; one left over gives nothing, and using it says why.
 */
public class ExtractorBlockEntity extends KineticBlockEntity {
    /** Fluid is counted in droplets: 81 to the millibucket. */
    private static final int MB = 81;
    /** Rotation needed per 100 mB on a normal node: 150 mB a second at 32 RPM. */
    private static final int CYCLE = 426;
    /** Nitrogen made per {@link #CYCLE}, in mB. */
    private static final int MB_PER_CYCLE = 100;
    /** Size of the output tank, in mB. */
    private static final int TANK_MB = 4000;
    /** How often the node under it is looked up again, in ticks. */
    private static final int NODE_RECHECK_TICKS = 40;
    /** Stress impact per RPM. */
    private static final float STRESS_IMPACT = 8f;

    public SmartFluidTankBehaviour tank;
    private float progress;
    private Optional<Node> node = Optional.empty();
    private int recheck;
    /** What is wrong right now ("" if nothing), as a lang key: no running Pressurizer near, or every one near is full. */
    public String problem = "";

    public ExtractorBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour<?>> behaviours) {
        super.addBehaviours(behaviours);
        tank = SmartFluidTankBehaviour.single(this, TANK_MB * MB).forbidInsertion();
        behaviours.add(tank);
    }

    @Override
    public void tick() {
        super.tick();
        if (!(level instanceof ServerLevel server)) return;
        if (recheck-- <= 0) {
            recheck = NODE_RECHECK_TICKS;
            node = Mining.nodeUnder(server, worldPosition).filter(n -> n.type() == NodeType.NITROGEN);
        }
        float speed = Math.abs(getSpeed());
        FluidEntry nitrogen = ModFluids.ALL.get("nitrogen");
        if (node.isEmpty() || nitrogen == null) return;
        // it asks for its well every tick, turning or not, so it keeps its place at its Pressurizer
        var company = Ownership.of(this);
        var draw = ExtractorLogic.draw(server, worldPosition, company == null ? "" : company.id);
        problem = draw.purity() != null ? "" : draw.full() ? "siftec.building.well_full" : "siftec.building.no_well";
        if (speed == 0 || draw.purity() == null) return;
        progress += speed * node.get().purity().multiplier;
        while (progress >= CYCLE) {
            progress -= CYCLE;
            if (tank.getPrimaryHandler().insert(new FluidStack(nitrogen.still, MB_PER_CYCLE * MB)) == 0) {
                progress = 0;
                break;
            }
        }
    }

    @Override
    public float calculateStressApplied() {
        lastStressApplied = STRESS_IMPACT;
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
