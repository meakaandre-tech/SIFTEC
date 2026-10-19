package com.meakaandre.siftec.blueprint;

import com.meakaandre.siftec.block.Facing;
import com.meakaandre.siftec.building.ControllerBlockEntity;
import com.meakaandre.siftec.building.Placement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Blueprint Designer's controller (its terminal): a Build Gun building's controller that also keeps its build volume
 * marked with sparks along the frame, and known to the flight check. The volume is the cube over the building's floor,
 * behind the terminal ({@code tools/structures.py}, blueprint_designer); it is no part of the building.
 */
public class DesignerBlockEntity extends ControllerBlockEntity {
    /** Loaded designers: where each one's terminal is, and the game time it last reported in. */
    private static final Map<ResourceKey<Level>, Map<BlockPos, Long>> LIVE = new ConcurrentHashMap<>();

    public DesignerBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public int reach() {
        return getBlockState().getBlock() instanceof DesignerBlock block ? block.reach : 4;
    }

    /**
     * The build volume: the cube over the floor behind the terminal, {@code 2 * reach + 1} blocks each way, its bottom
     * row level with the terminal. Worked out from the building's placement (the template's cells x -reach to reach,
     * y 1 to side, z 1 to side), or from the way the terminal faces if the placement is not known.
     */
    public AABB volume() {
        int reach = reach();
        Placement p = placement();
        if (p != null) return volume(p, reach);
        BlockState state = getBlockState();
        return volume(worldPosition, state.hasProperty(Facing.FACING) ? state.getValue(Facing.FACING) : Direction.SOUTH, reach);
    }

    /** The volume of a Blueprint Designer building placed so, of the given reach. */
    public static AABB volume(Placement p, int reach) {
        int side = 2 * reach + 1;
        BlockPos a = p.at(-reach, 1, 1), b = p.at(reach, side, side);
        return new AABB(Math.min(a.getX(), b.getX()), a.getY(), Math.min(a.getZ(), b.getZ()),
            Math.max(a.getX(), b.getX()) + 1, b.getY() + 1, Math.max(a.getZ(), b.getZ()) + 1);
    }

    /** The volume of a terminal at {@code terminal} facing {@code out} (away from the floor), of the given reach. */
    public static AABB volume(BlockPos terminal, Direction out, int reach) {
        int side = 2 * reach + 1;
        BlockPos middle = terminal.relative(out.getOpposite(), reach + 1);
        return new AABB(middle.getX() - reach, terminal.getY(), middle.getZ() - reach, middle.getX() + reach + 1, terminal.getY() + side, middle.getZ() + reach + 1);
    }

    /**
     * The open space inside a building that is no part of it: a Blueprint Designer's volume (null for any other
     * building). What stands there is the players' own: no building check counts it as the building's space, and
     * dismantling the building leaves it.
     */
    public static @Nullable AABB openSpace(ControllerBlockEntity controller) {
        Placement p = controller.placement();
        return p == null ? null : openSpace(p);
    }

    /** As {@link #openSpace(ControllerBlockEntity)}, for a building placed so (its controller may be gone already). */
    public static @Nullable AABB openSpace(Placement p) {
        com.meakaandre.siftec.building.BuildingTemplate t = com.meakaandre.siftec.building.BuildingTemplate.of(p.def());
        return t != null && t.controller.state().getBlock() instanceof DesignerBlock designer ? volume(p, designer.reach) : null;
    }

    /** Puts this designer on the list the flight check reads (every couple of seconds while it ticks, and when it is built). */
    public void report() {
        if (level == null || level.isClientSide()) return;
        LIVE.computeIfAbsent(level.dimension(), k -> new ConcurrentHashMap<>()).put(worldPosition.immutable(), level.getGameTime());
    }

    @Override
    public void serverTick() {
        super.serverTick();
        if (!(level instanceof ServerLevel server) || isRemoved() || placement() == null || (level.getGameTime() + worldPosition.hashCode()) % 40 != 0) return;
        report();
        AABB box = volume();
        int reach = reach();
        // the twelve edges of the volume
        for (double t = 0; t <= 1.0001; t += 1.0 / (2 * reach + 1)) {
            double x = box.minX + box.getXsize() * t, y = box.minY + box.getYsize() * t, z = box.minZ + box.getZsize() * t;
            for (int i = 0; i < 4; i++) {
                double a = (i & 1) == 0 ? 0 : 1, b = (i & 2) == 0 ? 0 : 1;
                server.sendParticles(ParticleTypes.END_ROD, x, box.minY + box.getYsize() * a, box.minZ + box.getZsize() * b, 1, 0, 0, 0, 0);
                server.sendParticles(ParticleTypes.END_ROD, box.minX + box.getXsize() * a, y, box.minZ + box.getZsize() * b, 1, 0, 0, 0, 0);
                server.sendParticles(ParticleTypes.END_ROD, box.minX + box.getXsize() * a, box.minY + box.getYsize() * b, z, 1, 0, 0, 0, 0);
            }
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level != null) {
            Map<BlockPos, Long> map = LIVE.get(level.dimension());
            if (map != null) map.remove(worldPosition);
        }
    }

    /** True while the player is inside the volume of a loaded Blueprint Designer. Anyone may fly there. */
    public static boolean inside(ServerPlayer player) {
        Map<BlockPos, Long> map = LIVE.get(player.level().dimension());
        if (map == null) return false;
        long now = player.level().getGameTime();
        for (Map.Entry<BlockPos, Long> entry : map.entrySet()) {
            if (now - entry.getValue() > 200) continue;
            if (!(player.level().getBlockEntity(entry.getKey()) instanceof DesignerBlockEntity designer) || designer.placement() == null) continue;
            if (designer.volume().inflate(0.5).contains(player.position())) return true;
        }
        return false;
    }

    public static void clear() {
        LIVE.clear();
    }
}
