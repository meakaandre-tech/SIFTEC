package com.meakaandre.siftec.power;

import com.meakaandre.siftec.registry.ModItems;
import com.zurrtum.create.content.kinetics.base.IRotate;
import com.zurrtum.create.content.kinetics.base.KineticBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class PoleBlockEntity extends KineticBlockEntity {
    public static final int MAX_LINES = 4;
    /** Linked poles, as offsets from this one. */
    public final Set<BlockPos> lines = new LinkedHashSet<>();
    private int sparkle;

    public PoleBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public boolean link(BlockPos other) {
        if (!lines.add(other.subtract(worldPosition))) return false;
        relink();
        return true;
    }

    public void unlink(BlockPos other) {
        if (lines.remove(other.subtract(worldPosition))) relink();
    }

    /** Makes the kinetic network look at this pole's connections again. */
    private void relink() {
        notifyUpdate();
        detachKinetics();
        updateSpeed = true;
    }

    @Override
    public List<BlockPos> addPropagationLocations(IRotate block, BlockState state, List<BlockPos> neighbours) {
        lines.forEach(offset -> neighbours.add(worldPosition.offset(offset)));
        return super.addPropagationLocations(block, state, neighbours);
    }

    @Override
    public float propagateRotationTo(KineticBlockEntity target, BlockState stateFrom, BlockState stateTo, BlockPos diff,
                                     boolean connectedViaAxes, boolean connectedViaCogs) {
        if (target instanceof PoleBlockEntity && lines.contains(target.getBlockPos().subtract(worldPosition))) return 1;
        return super.propagateRotationTo(target, stateFrom, stateTo, diff, connectedViaAxes, connectedViaCogs);
    }

    @Override
    public boolean isCustomConnection(KineticBlockEntity other, BlockState state, BlockState otherState) {
        return other instanceof PoleBlockEntity && lines.contains(other.getBlockPos().subtract(worldPosition));
    }

    @Override
    public void tick() {
        super.tick();
        if (!(level instanceof ServerLevel server) || lines.isEmpty() || ++sparkle < 30) return;
        sparkle = 0;
        // until the lines get a proper model, sparks drift along them so you can see where they go
        for (BlockPos offset : lines) {
            // each line is drawn once, from the end that sorts first
            if (offset.compareTo(BlockPos.ZERO) < 0) continue;
            double length = Math.sqrt(offset.distSqr(BlockPos.ZERO));
            int points = Math.max(2, (int) (length / 1.5));
            for (int i = 0; i <= points; i++) {
                double t = i / (double) points;
                double sag = Math.sin(t * Math.PI) * Math.min(1.5, length / 12);
                server.sendParticles(ParticleTypes.WAX_OFF,
                    worldPosition.getX() + 0.5 + offset.getX() * t, worldPosition.getY() + 0.95 + offset.getY() * t - sag,
                    worldPosition.getZ() + 0.5 + offset.getZ() * t, 1, 0, 0, 0, 0);
            }
        }
    }

    @Override
    public void destroy() {
        super.destroy();
        if (level == null) return;
        // the lines come back as items, and the poles at the other ends let go
        for (BlockPos offset : new ArrayList<>(lines)) {
            if (level.getBlockEntity(worldPosition.offset(offset)) instanceof PoleBlockEntity other) other.unlink(worldPosition);
            level.addFreshEntity(new ItemEntity(level, worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5,
                new ItemStack(ModItems.POWER_LINE.get())));
        }
        lines.clear();
    }

    @Override
    protected void write(ValueOutput view, boolean clientPacket) {
        super.write(view, clientPacket);
        view.store("Lines", BlockPos.CODEC.listOf(), new ArrayList<>(lines));
    }

    @Override
    protected void read(ValueInput view, boolean clientPacket) {
        super.read(view, clientPacket);
        lines.clear();
        lines.addAll(view.read("Lines", BlockPos.CODEC.listOf()).orElse(List.of()));
    }
}
