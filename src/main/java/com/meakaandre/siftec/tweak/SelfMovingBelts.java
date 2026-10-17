package com.meakaandre.siftec.tweak;

import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.owner.Ownership;
import com.zurrtum.create.content.kinetics.RotationPropagator;
import com.zurrtum.create.content.kinetics.belt.BeltBlockEntity;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

/**
 * Belts that move by themselves, like Satisfactory's conveyors. Every belt runs at its company's self-moving speed,
 * which the Logistics milestones raise through Satisfactory's belt tiers (milestone tokens "belt:&lt;items a minute&gt;"),
 * and nothing can stop it or change its speed.
 * <p>
 * Belts never connect to shafts: a belt takes no rotation from a shaft and gives none to one, its ends show no shaft
 * or pulley, and placing one (on shafts, as in Create) never links it into a kinetic network (see the belt mixins). A
 * belt that a world saved while it was still part of a network is taken out of it on its first tick, and so is anything
 * it was turning.
 * <p>
 * A Create belt carries at most one stack per block and moves speed / 480 blocks a tick, so single items go
 * past at 2.5 a minute per RPM: Belt Mk.1's 60 items a minute is 24 RPM, Mk.6's 1,200 is 480. Items run from
 * the end the belt was started at toward the other end.
 * <p>
 * The speed is worked out on the server for each belt's controller (the block at its start) and sent to
 * clients with the belt's own data; every block of the belt reads it from the controller. It is never the
 * belt's real speed: nothing else in the kinetic network sees it, so a belt turns no shafts.
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

    /** The speed every block of a belt runs at: its controller's self-moving speed. */
    public static float speed(BeltBlockEntity belt) {
        BeltBlockEntity controller = belt.isController() ? belt : belt.getControllerBE();
        if (controller == null) return 0;
        return ((Holder) controller).siftec$selfSpeed();
    }

    /**
     * Called at the start of every belt block's tick: takes the block out of any kinetic network it still belongs to
     * (an older world), and keeps a controller's self-moving speed up to date.
     */
    public static void tick(BeltBlockEntity belt) {
        if (!(belt.getLevel() instanceof ServerLevel level)) return;
        detach(level, belt);
        if (!belt.isController()) return;
        Holder holder = (Holder) belt;
        if (holder.siftec$checked() && (level.getGameTime() + belt.getBlockPos().hashCode()) % CHECK_TICKS != 0) return;
        holder.siftec$checked(true);
        float want = rpm(itemsPerMinute(Ownership.of(belt))) * forward(belt);
        if (want == holder.siftec$selfSpeed()) return;
        holder.siftec$selfSpeed(want);
        belt.sendData();
    }

    /**
     * True if the belt block is part of a kinetic network, has a source or turns of its own: none of which a belt may
     * have. Cheap: three fields.
     */
    public static boolean linked(BeltBlockEntity belt) {
        return belt.hasSource() || belt.hasNetwork() || belt.getTheoreticalSpeed() != 0;
    }

    /**
     * Takes a belt block out of the kinetic network: whatever it was turning (a shaft at its end that took its rotation
     * from it) loses it, as when a block is broken, and the belt keeps no source, network or speed of its own.
     */
    public static void detach(ServerLevel level, BeltBlockEntity belt) {
        if (!linked(belt)) return;
        if (belt.getTheoreticalSpeed() != 0) RotationPropagator.handleRemoved(level, belt.getBlockPos(), belt);
        belt.removeSource();
        belt.sendData();
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
