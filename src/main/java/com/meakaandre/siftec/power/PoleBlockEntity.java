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
        if (level instanceof ServerLevel server) PowerGrid.get(server).link(server, worldPosition, other);
        relink();
        return true;
    }

    public void unlink(BlockPos other) {
        if (lines.remove(other.subtract(worldPosition))) {
            if (level instanceof ServerLevel server) PowerGrid.get(server).unlink(server, worldPosition, other);
            relink();
        }
    }

    /** Makes the kinetic network look at this pole's connections again. */
    private void relink() {
        notifyUpdate();
        detachKinetics();
        updateSpeed = true;
    }

    /**
     * Linked poles are kinetic neighbours. A linked pole in an unloaded chunk is not read (Create would load its
     * chunk on the spot, at every start and stop of any source on the line); instead the line is followed through
     * the {@link PowerGrid}, which knows every line without loading anything, past all the unloaded poles to the
     * next loaded ones, and those are the neighbours. So a long line whose middle is unloaded still joins its two
     * loaded ends into one network: rotation, direction, speed changes, stops and stress all cross it.
     */
    public Set<BlockPos> poleNeighbours() {
        if (level == null) return Set.of();
        if (!(level instanceof ServerLevel server)) {
            Set<BlockPos> out = new LinkedHashSet<>();
            for (BlockPos offset : lines) out.add(worldPosition.offset(offset));
            return out;
        }
        PowerGrid grid = PowerGrid.get(server);
        long now = server.getGameTime();
        if (cached != null && cachedAt == now && cachedVersion == grid.version()) return cached;
        Set<BlockPos> out = new LinkedHashSet<>();
        for (BlockPos offset : lines) {
            BlockPos other = worldPosition.offset(offset);
            if (server.isLoaded(other)) out.add(other);
            else out.addAll(grid.loadedBeyond(server, worldPosition, other));
        }
        out.remove(worldPosition);
        cached = out;
        cachedAt = now;
        cachedVersion = grid.version();
        return out;
    }

    private Set<BlockPos> cached;
    private long cachedAt;
    private int cachedVersion;

    @Override
    public List<BlockPos> addPropagationLocations(IRotate block, BlockState state, List<BlockPos> neighbours) {
        neighbours.addAll(poleNeighbours());
        return super.addPropagationLocations(block, state, neighbours);
    }

    @Override
    public float propagateRotationTo(KineticBlockEntity target, BlockState stateFrom, BlockState stateTo, BlockPos diff,
                                     boolean connectedViaAxes, boolean connectedViaCogs) {
        if (target instanceof PoleBlockEntity && poleNeighbours().contains(target.getBlockPos())) return 1;
        return super.propagateRotationTo(target, stateFrom, stateTo, diff, connectedViaAxes, connectedViaCogs);
    }

    @Override
    public boolean isCustomConnection(KineticBlockEntity other, BlockState state, BlockState otherState) {
        return other instanceof PoleBlockEntity && poleNeighbours().contains(other.getBlockPos());
    }

    /**
     * A pole loaded again: the map is the truth for lines cut while it was away (their line item was already given
     * back where they were cut); a pole the map has never seen (built before it existed) tells the map its lines.
     */
    private void joinGrid(ServerLevel server) {
        PowerGrid grid = PowerGrid.get(server);
        if (!grid.knows(server, worldPosition)) {
            List<BlockPos> ends = new ArrayList<>();
            for (BlockPos offset : lines) ends.add(worldPosition.offset(offset));
            grid.add(server, worldPosition, ends);
            return;
        }
        Set<BlockPos> kept = grid.links(server, worldPosition);
        // a line the map has but this pole does not cannot be real (a link always goes into both): it goes
        for (BlockPos end : kept) if (!lines.contains(end.subtract(worldPosition))) grid.unlink(server, worldPosition, end);
        if (lines.removeIf(offset -> !kept.contains(worldPosition.offset(offset)))) {
            notifyUpdate();
            setChanged();
        }
    }

    private boolean joined;

    /**
     * What a pole's source must be: the shaft below it, or one of its pole neighbours as they are now. When the
     * land along a line loads or unloads, the neighbours change (the next loaded pole is a different one), and a
     * source that is no longer a neighbour, or turns at another speed than this pole, is let go the way Create lets
     * go of a broken source: what hung on it is cleared and it takes the rotation again from whatever still turns.
     * That is also what cuts the far end when a pole in the middle is broken.
     */
    private void checkSource() {
        if (!hasSource()) return;
        BlockPos from = source;
        if (from.equals(worldPosition.below())) return;
        boolean ok = poleNeighbours().contains(from) && level.getBlockEntity(from) instanceof PoleBlockEntity pole
            && Math.abs(pole.getTheoreticalSpeed() - getTheoreticalSpeed()) <= 1.0e-3f;
        if (!ok) com.meakaandre.siftec.mixin.PropagatorInvoker.siftec$propagateMissingSource(this);
    }

    @Override
    public void tick() {
        super.tick();
        if (!(level instanceof ServerLevel server)) return;
        if (!joined) {
            joined = true;
            joinGrid(server);
        }
        if (!moved.isEmpty()) letGoAfterMove(server);
        if ((server.getGameTime() + worldPosition.hashCode()) % 10 == 0) checkSource();
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
     * Its offsets would now point at the wrong places, so the lines are dropped and each comes back as an item here.
     * A loaded far pole lets go at once; for an unloaded one the {@link PowerGrid} lets go, and the pole drops its
     * end when it loads. No line is ever paid out twice.
     */
    private final List<BlockPos[]> moved = new ArrayList<>();

    private void letGoAfterMove(ServerLevel server) {
        PowerGrid grid = PowerGrid.get(server);
        for (BlockPos[] line : moved) {
            if (!server.isLoaded(line[0])) {
                // the far pole is not loaded: the map lets go for it (it drops the line when it loads), the item comes back here
                if (!grid.linked(server, line[0], line[1])) continue;
                grid.unlink(server, line[0], line[1]);
            } else {
                if (!(server.getBlockEntity(line[0]) instanceof PoleBlockEntity other)) continue;
                if (!other.lines.contains(line[1].subtract(line[0]))) continue;
                other.unlink(line[1]);
            }
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
        // the lines come back as items, and the poles at the other ends let go: a loaded one at once, an unloaded one
        // (not loaded for this) when it loads again, as the map no longer has the line
        for (BlockPos offset : new ArrayList<>(lines)) {
            BlockPos end = worldPosition.offset(offset);
            if (level.isLoaded(end) && level.getBlockEntity(end) instanceof PoleBlockEntity other) other.unlink(worldPosition);
            level.addFreshEntity(new ItemEntity(level, worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5,
                new ItemStack(ModItems.POWER_LINE.get())));
        }
        lines.clear();
        if (level instanceof ServerLevel server) PowerGrid.get(server).remove(server, worldPosition);
    }

    /** Taken away without being broken (a contraption picked it up): its node leaves the map, but not on a chunk unload. */
    @Override
    public void remove() {
        super.remove();
        if (level instanceof ServerLevel server && server.isLoaded(worldPosition) && !(server.getBlockState(worldPosition).getBlock() instanceof PoleBlock)) {
            PowerGrid.get(server).remove(server, worldPosition);
        }
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
