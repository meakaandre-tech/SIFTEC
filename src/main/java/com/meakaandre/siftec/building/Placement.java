package com.meakaandre.siftec.building;

import com.meakaandre.siftec.prefab.Prefabs;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * Where and how a building goes: the middle of its front edge ({@code anchor}, its bottom row), the way it stretches
 * from there ({@code facing}, rotated with R) and whether it is mirrored left to right ({@code flip}, F).
 */
public record Placement(BuildingDef def, BlockPos anchor, Direction facing, boolean flip) {
    /** A cell of the template (Build Gun frame) in the world. */
    public BlockPos at(int x, int y, int z) {
        int fx = flip ? -x : x;
        Direction right = facing.getClockWise();
        return new BlockPos(anchor.getX() + right.getStepX() * fx + facing.getStepX() * z, anchor.getY() + y,
            anchor.getZ() + right.getStepZ() * fx + facing.getStepZ() * z);
    }

    public BlockPos at(BuildingTemplate.Cell cell) {
        return at(cell.x(), cell.y(), cell.z());
    }

    /** A block state as authored (facing north), turned and mirrored to this placement. */
    public BlockState orient(BlockState state) {
        if (flip) state = state.mirror(Mirror.FRONT_BACK);
        return state.rotate(Prefabs.rotation(facing));
    }

    public Direction orient(Direction direction) {
        if (flip) direction = Mirror.FRONT_BACK.mirror(direction);
        return Prefabs.rotation(facing).rotate(direction);
    }

    /** The building's whole box in the world. */
    public AABB bounds(BuildingTemplate t) {
        BlockPos a = at(t.minX, 0, 0), b = at(t.maxX, t.height - 1, t.depth - 1);
        return new AABB(Math.min(a.getX(), b.getX()), a.getY(), Math.min(a.getZ(), b.getZ()),
            Math.max(a.getX(), b.getX()) + 1, b.getY() + 1, Math.max(a.getZ(), b.getZ()) + 1);
    }

    public Placement moved(int dx, int dy, int dz) {
        return new Placement(def, anchor.offset(dx, dy, dz), facing, flip);
    }

    public Placement turned() {
        return new Placement(def, anchor, facing.getClockWise(), flip);
    }

    public Placement flipped() {
        return new Placement(def, anchor, facing, !flip);
    }
}
