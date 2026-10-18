package com.meakaandre.siftec.sim;

import com.meakaandre.siftec.building.BuildingIndex;
import com.meakaandre.siftec.building.PortBlock;
import com.meakaandre.siftec.building.PortBlockEntity;
import com.meakaandre.siftec.tweak.SelfMovingBelts;
import com.zurrtum.create.content.fluids.FluidPropagator;
import com.zurrtum.create.content.fluids.FluidTransportBehaviour;
import com.zurrtum.create.content.kinetics.belt.BeltBlockEntity;
import com.zurrtum.create.content.kinetics.belt.BeltHelper;
import com.zurrtum.create.content.logistics.depot.DepotBlockEntity;
import com.zurrtum.create.content.logistics.tunnel.BeltTunnelBlock;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Follows what leaves a building's output port to where it ends up, while it is loaded (SimRecorder): along the belts
 * in front of it (their length and speed give the time things take and how fast they carry; a tunnel on them splits what
 * passes evenly onto the belts beside it, as the pack's tunnels do) to the building whose input port the last belt runs
 * into, or the inventory it ends in; and along the pipes from a fluid output to the fluid input they reach.
 */
final class Tracer {
    /** Where a link ends: a building's controller (by its input port), or a plain inventory. */
    record End(BlockPos node, boolean inventory, BlockPos at, double rate, long delay, long cap, double share) {
    }

    private static final int MOST_STEPS = 64;
    /** A port pushes one item a tick into what is in front of it. */
    private static final double PORT_RATE = 1;
    /** mB a tick a pipe carries (Satisfactory's Pipeline Mk.1, 300 m3 a minute at 40 mB a m3, 10 a tick; twice that). */
    static final double PIPE_RATE = 20;

    private Tracer() {
    }

    static List<End> items(ServerLevel level, BlockPos port, Direction out) {
        List<End> ends = new ArrayList<>();
        walk(level, port.relative(out), out, PORT_RATE, 1, 0, 1, ends, new HashSet<>(), 0);
        return ends;
    }

    private static void walk(ServerLevel level, BlockPos pos, Direction dir, double rate, long delay, long cap, double share,
                             List<End> ends, Set<BlockPos> seen, int depth) {
        if (depth > MOST_STEPS || !level.isLoaded(pos) || !seen.add(pos.immutable())) return;
        BlockEntity be = level.getBlockEntity(pos);
        BlockState state = level.getBlockState(pos);
        if (be instanceof PortBlockEntity port) {
            if (state.hasProperty(PortBlock.KIND) && state.getValue(PortBlock.KIND) == PortBlock.Kind.ITEM_IN
                && state.getValue(PortBlock.FACING) == dir.getOpposite() && port.controller != null) {
                ends.add(new End(port.controller, false, pos.immutable(), rate, delay, cap + 2, share));
            }
            return;
        }
        if (be instanceof BeltBlockEntity belt) {
            belt(level, belt, rate, delay, cap, share, ends, seen, depth);
            return;
        }
        if (be instanceof DepotBlockEntity) return;
        if (BuildingIndex.controllerOf(level, pos) != null) return;
        if (ItemStorage.SIDED.find(level, pos, dir.getOpposite()) != null) {
            ends.add(new End(pos.immutable(), true, pos.immutable(), rate, delay, cap + 2, share));
        }
    }

    private static void belt(ServerLevel level, BeltBlockEntity belt, double rate, long delay, long cap, double share,
                             List<End> ends, Set<BlockPos> seen, int depth) {
        BeltBlockEntity ctrl = belt.isController() ? belt : belt.getControllerBE();
        if (ctrl == null) return;
        float rpm = Math.abs(SelfMovingBelts.speed(ctrl));
        if (rpm == 0) rpm = Math.abs(ctrl.getSpeed());
        // a belt that does not move takes things nowhere
        if (rpm == 0) return;
        int len = Math.max(1, ctrl.beltLength);
        List<BlockPos> segs = new ArrayList<>(len);
        for (int i = 0; i < len; i++) segs.add(BeltHelper.getPositionForOffset(ctrl, i));
        Direction moving = ctrl.getMovementFacing();
        int entry = Math.max(0, Math.min(len - 1, belt.index));
        boolean forward = true;
        if (len > 1) {
            BlockPos a = segs.getFirst(), b = segs.getLast();
            int dot = (b.getX() - a.getX()) * moving.getStepX() + (b.getZ() - a.getZ()) * moving.getStepZ();
            forward = dot >= 0;
        }
        int end = forward ? len - 1 : 0, step = forward ? 1 : -1;
        double perTick = rpm / 480.0;
        double beltRate = rpm * SelfMovingBelts.ITEMS_PER_MINUTE_PER_RPM / 1200.0;
        double r = Math.min(rate, beltRate);
        // a tunnel on the belt splits what passes: straight on, and onto each belt beside it running away from it
        int travelled = 0;
        for (int i = entry; ; i += step) {
            BlockPos seg = segs.get(i);
            // a segment in a chunk that is not loaded: the belt is not followed past it (reading it would load the chunk)
            if (!level.isLoaded(seg)) return;
            seen.add(seg.immutable());
            travelled++;
            if (level.getBlockState(seg.above()).getBlock() instanceof BeltTunnelBlock) {
                List<Direction> sides = new ArrayList<>();
                for (Direction side : new Direction[]{moving.getClockWise(), moving.getCounterClockWise()}) {
                    BlockPos next = seg.relative(side);
                    BlockEntity sb = level.isLoaded(next) ? level.getBlockEntity(next) : null;
                    if (sb instanceof BeltBlockEntity sideBelt && sideBelt.getMovementFacing() == side) sides.add(side);
                    else if (sb instanceof PortBlockEntity && level.getBlockState(next).hasProperty(PortBlock.FACING)
                        && level.getBlockState(next).getValue(PortBlock.FACING) == side.getOpposite()) sides.add(side);
                }
                if (!sides.isEmpty()) {
                    double part = share / (sides.size() + 1);
                    long at = delay + (long) Math.ceil(travelled / perTick);
                    for (Direction side : sides) walk(level, seg.relative(side), side, r, at, cap + travelled * 2L, part, ends, seen, depth + 1);
                    share = part;
                }
            }
            if (i == end) break;
        }
        long time = delay + (long) Math.ceil(travelled / perTick);
        BlockPos last = segs.get(end);
        BlockPos next = last.relative(moving);
        // a sloped end hands on up or down a block
        if (!usable(level, next)) {
            if (usable(level, next.above())) next = next.above();
            else if (usable(level, next.below())) next = next.below();
        }
        walk(level, next, moving, r, time, cap + travelled * 2L, share, ends, seen, depth + 1);
    }

    private static boolean usable(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) return false;
        BlockEntity be = level.getBlockEntity(pos);
        return be instanceof PortBlockEntity || be instanceof BeltBlockEntity || ItemStorage.SIDED.find(level, pos, null) != null;
    }

    // ------------------------------------------------------------------------------------------------ pipes

    /** The fluid input ports (by their controller) the pipes in front of a fluid output reach, nearest first. */
    static List<End> fluids(ServerLevel level, BlockPos port, Direction out) {
        List<End> ends = new ArrayList<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        Map<BlockPos, Integer> dist = new HashMap<>();
        BlockPos first = port.relative(out);
        queue.add(first);
        dist.put(first, 1);
        while (!queue.isEmpty() && dist.size() < 512) {
            BlockPos pos = queue.poll();
            if (!level.isLoaded(pos)) continue;
            int d = dist.get(pos);
            BlockEntity be = level.getBlockEntity(pos);
            BlockState state = level.getBlockState(pos);
            if (be instanceof PortBlockEntity p) {
                if (!pos.equals(port) && state.hasProperty(PortBlock.KIND) && state.getValue(PortBlock.KIND) == PortBlock.Kind.FLUID_IN && p.controller != null) {
                    ends.add(new End(p.controller, false, pos.immutable(), PIPE_RATE, d, 250L + d * 50L, 1));
                }
                continue;
            }
            FluidTransportBehaviour pipe;
            try {
                pipe = FluidPropagator.getPipe(level, pos);
            } catch (RuntimeException e) {
                pipe = null;
            }
            if (pipe == null) continue;
            List<Direction> connections;
            try {
                connections = FluidPropagator.getPipeConnections(state, pipe);
            } catch (RuntimeException e) {
                connections = List.of(Direction.values());
            }
            for (Direction c : connections) {
                BlockPos next = pos.relative(c);
                if (dist.containsKey(next) || next.equals(port)) continue;
                dist.put(next.immutable(), d + 1);
                queue.add(next.immutable());
            }
        }
        // one fluid output feeding several inputs shares what it gives between them
        List<End> shared = new ArrayList<>();
        for (End e : ends) shared.add(new End(e.node, false, e.at, e.rate, e.delay, e.cap, 1.0 / ends.size()));
        return shared;
    }
}
