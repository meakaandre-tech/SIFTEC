package com.meakaandre.siftec.tweak;

import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.owner.Ownership;
import com.zurrtum.create.content.kinetics.RotationPropagator;
import com.zurrtum.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.zurrtum.create.content.kinetics.base.IRotate;
import com.zurrtum.create.content.kinetics.base.KineticBlockEntity;
import com.zurrtum.create.content.kinetics.belt.BeltBlockEntity;
import com.zurrtum.create.content.kinetics.belt.BeltHelper;
import com.zurrtum.create.content.kinetics.transmission.SplitShaftBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * Belts that move by themselves. A belt with no drive at all runs at its company's self-moving speed, which the
 * Logistics milestones raise through Satisfactory's belt tiers (milestone tokens "belt:&lt;items a minute&gt;").
 * A belt a shaft drives runs at that shaft's speed, as in Create. A belt that has a drive (a motor, water wheel, hand
 * crank, anything that makes rotation) somewhere along its shafts but is cut off from it, by an engaged clutch or a
 * gearshift (a Sequenced Gearshift between steps), stands still as in Create: only {@link #drive} finding nothing
 * lets a belt move by itself.
 * <p>
 * A Create belt carries at most one stack per block and moves speed / 480 blocks a tick, so single items go
 * past at 2.5 a minute per RPM: Belt Mk.1's 60 items a minute is 24 RPM, Mk.6's 1,200 is 480. Items run from
 * the end the belt was started at toward the other end.
 * <p>
 * The speed is worked out on the server for each belt's controller (the block at its start) and sent to
 * clients with the belt's own data; every block of the belt reads it from the controller. It is never the
 * belt's real speed: nothing else in the kinetic network sees it, so a self-moving belt turns no shafts.
 */
public final class SelfMovingBelts {
    public static final float ITEMS_PER_MINUTE_PER_RPM = 2.5f;
    private static final int CHECK_TICKS = 20;

    /** What the belt mixin adds to every belt. */
    public interface Holder {
        float siftec$selfSpeed();

        void siftec$selfSpeed(float speed);

        boolean siftec$checked();

        void siftec$checked(boolean checked);
    }

    private SelfMovingBelts() {
    }

    /** A company's self-moving belt speed in items a minute: 0 until Logistics. */
    public static int itemsPerMinute(@Nullable Company company) {
        return company == null ? 0 : company.best("belt:", 0);
    }

    /** The belt speed, in Create's RPM, that carries this many single items a minute. */
    public static float rpm(int itemsPerMinute) {
        return itemsPerMinute / ITEMS_PER_MINUTE_PER_RPM;
    }

    /** The speed a belt shows when Create has none for it: its controller's self-moving speed, or 0 if a shaft drives it. */
    public static float speed(BeltBlockEntity belt) {
        if (belt.hasSource()) return 0;
        BeltBlockEntity controller = belt.isController() ? belt : belt.getControllerBE();
        if (controller == null || controller.hasSource()) return 0;
        return ((Holder) controller).siftec$selfSpeed();
    }

    /** Called at the start of every belt block's tick; keeps a controller's self-moving speed up to date. */
    public static void tick(BeltBlockEntity belt) {
        if (!(belt.getLevel() instanceof ServerLevel level) || !belt.isController()) return;
        Holder holder = (Holder) belt;
        if (holder.siftec$checked() && (level.getGameTime() + belt.getBlockPos().hashCode()) % CHECK_TICKS != 0) return;
        holder.siftec$checked(true);
        float want = belt.hasSource() || drive(level, belt) ? 0 : rpm(itemsPerMinute(Ownership.of(belt))) * forward(belt);
        if (want == holder.siftec$selfSpeed()) return;
        holder.siftec$selfSpeed(want);
        belt.sendData();
    }

    /** Most kinetic blocks {@link #drive} looks through; far more than a belt's own machinery. */
    private static final int MAX_WALK = 1024;

    /**
     * True if the belt has a drive anywhere along what it is built into: every kinetic block joined to it as Create
     * joins them (shafts, cogs, the belt's own segments), and on through clutches and gearshifts whatever they are set
     * to, since a clutch that is engaged still stands between the belt and its drive. Reads only loaded blocks.
     */
    public static boolean drive(Level level, BeltBlockEntity belt) {
        BeltBlockEntity controller = belt.isController() ? belt : belt.getControllerBE();
        if (controller == null) return false;
        Set<BlockPos> seen = new HashSet<>();
        Deque<KineticBlockEntity> queue = new ArrayDeque<>();
        for (int i = 0; i < Math.max(1, controller.beltLength); i++) {
            BeltBlockEntity segment = BeltHelper.getBeltAtSegment(controller, i);
            if (segment != null && seen.add(segment.getBlockPos())) queue.add(segment);
        }
        while (!queue.isEmpty() && seen.size() < MAX_WALK) {
            KineticBlockEntity k = queue.poll();
            if (k instanceof GeneratingKineticBlockEntity) return true;
            BlockPos at = k.getBlockPos();
            for (BlockPos p : BlockPos.betweenClosed(at.offset(-1, -1, -1), at.offset(1, 1, 1))) {
                if (seen.contains(p) || !level.isLoaded(p) || !(level.getBlockEntity(p) instanceof KineticBlockEntity n)) continue;
                if (RotationPropagator.isConnected(k, n) || throughSplit(level, k, n) || throughSplit(level, n, k)) {
                    seen.add(p.immutable());
                    queue.add(n);
                }
            }
        }
        return false;
    }

    /** A clutch or gearshift and the block on its axis next to it, joined whatever the clutch or gearshift is set to. */
    private static boolean throughSplit(Level level, KineticBlockEntity split, KineticBlockEntity other) {
        if (!(split instanceof SplitShaftBlockEntity)) return false;
        BlockState state = split.getBlockState(), otherState = other.getBlockState();
        if (!(state.getBlock() instanceof IRotate rotate) || !(otherState.getBlock() instanceof IRotate otherRotate)) return false;
        BlockPos diff = other.getBlockPos().subtract(split.getBlockPos());
        if (Math.abs(diff.getX()) + Math.abs(diff.getY()) + Math.abs(diff.getZ()) != 1) return false;
        Direction side = Direction.getNearest(diff.getX(), diff.getY(), diff.getZ(), Direction.UP);
        return side.getAxis() == rotate.getRotationAxis(state)
            && otherRotate.hasShaftTowards(level, other.getBlockPos(), otherState, side.getOpposite());
    }

    /** Works out a belt's self-moving speed again on its next tick (after a milestone, or when it changes hands). */
    public static void recheck(BeltBlockEntity belt) {
        ((Holder) belt).siftec$checked(false);
    }

    /** The sign of speed that moves items from the belt's start toward its end. */
    private static float forward(BeltBlockEntity belt) {
        Direction facing = belt.getBeltFacing();
        int step = facing.getAxisDirection().getStep();
        // Create turns X-axis belts round: see BeltBlockEntity.getDirectionAwareBeltMovementSpeed
        return facing.getAxis() == Direction.Axis.X ? -step : step;
    }
}
