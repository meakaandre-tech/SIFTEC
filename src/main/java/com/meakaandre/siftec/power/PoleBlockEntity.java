package com.meakaandre.siftec.power;

import com.meakaandre.siftec.registry.ModItems;
import com.zurrtum.create.content.kinetics.base.IRotate;
import com.zurrtum.create.content.kinetics.base.KineticBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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

    /**
     * Linked poles are kinetic neighbours, but only while their chunk is loaded: Create reads every neighbour it is
     * given, and a far pole in an unloaded chunk would be loaded from disk on the spot, at every start and stop of
     * any source on the line (a Geyser Engine does that every half minute). A line into unloaded land is cut there;
     * when that pole loads again it joins up from its own end, as it holds the same line.
     */
    @Override
    public List<BlockPos> addPropagationLocations(IRotate block, BlockState state, List<BlockPos> neighbours) {
        for (BlockPos offset : lines) {
            BlockPos other = worldPosition.offset(offset);
            if (level == null || level.isLoaded(other)) neighbours.add(other);
        }
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
        if (!(level instanceof ServerLevel server)) return;
        if (!moved.isEmpty()) letGoAfterMove(server);
        if ((level.getGameTime() + worldPosition.hashCode()) % 20 == 0) Poles.mark(level, worldPosition, getSpeed() != 0);
        if (lines.isEmpty() || ++sparkle < 60) return;
        sparkle = 0;
        // until the lines get a proper model, sparks along them show where they go: only for players close by,
        // and only the stretch near each of them
        for (ServerPlayer player : server.players()) {
            for (BlockPos offset : lines) {
                // each line is drawn once, from the end that sorts first
                if (offset.compareTo(BlockPos.ZERO) < 0) continue;
                double length = Math.sqrt(offset.distSqr(BlockPos.ZERO));
                int points = Math.max(2, (int) (length / 3));
                for (int i = 0; i <= points; i++) {
                    double t = i / (double) points;
                    double sag = Math.sin(t * Math.PI) * Math.min(1.5, length / 12);
                    double x = worldPosition.getX() + 0.5 + offset.getX() * t, y = worldPosition.getY() + 0.95 + offset.getY() * t - sag;
                    double z = worldPosition.getZ() + 0.5 + offset.getZ() * t;
                    if (player.distanceToSqr(x, y, z) > SPARK_RANGE * SPARK_RANGE) continue;
                    server.sendParticles(player, ParticleTypes.WAX_OFF, false, false, x, y, z, 1, 0, 0, 0, 0);
                }
            }
        }
    }

    private static final double SPARK_RANGE = 24;

    /**
     * Lines a pole had before a contraption carried it somewhere else (or it was copied): {far end, old position}.
     * Its offsets would now point at the wrong places, so the lines are dropped. A loaded far pole lets go too and
     * the line comes back as an item; an unloaded one keeps its end of the line until it is broken (it gives the
     * item back then), so no line is ever paid out twice.
     */
    private final List<BlockPos[]> moved = new ArrayList<>();

    private void letGoAfterMove(ServerLevel server) {
        for (BlockPos[] line : moved) {
            if (!server.isLoaded(line[0]) || !(server.getBlockEntity(line[0]) instanceof PoleBlockEntity other)) continue;
            if (!other.lines.contains(line[1].subtract(line[0]))) continue;
            other.unlink(line[1]);
            server.addFreshEntity(new ItemEntity(server, worldPosition.getX() + 0.5, worldPosition.getY() + 1.0, worldPosition.getZ() + 0.5,
                new ItemStack(ModItems.POWER_LINE.get())));
        }
        moved.clear();
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level != null && !level.isClientSide()) Poles.mark(level, worldPosition, false);
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
        view.store("At", BlockPos.CODEC, worldPosition);
    }

    @Override
    protected void read(ValueInput view, boolean clientPacket) {
        super.read(view, clientPacket);
        lines.clear();
        List<BlockPos> saved = view.read("Lines", BlockPos.CODEC.listOf()).orElse(List.of());
        BlockPos at = view.read("At", BlockPos.CODEC).orElse(worldPosition);
        if (!clientPacket && !at.equals(worldPosition)) {
            // carried here by a contraption (or copied): the lines stayed behind
            for (BlockPos offset : saved) moved.add(new BlockPos[]{at.offset(offset), at});
            return;
        }
        lines.addAll(saved);
    }
}
