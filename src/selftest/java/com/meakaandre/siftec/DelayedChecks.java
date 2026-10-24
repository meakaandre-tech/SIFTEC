package com.meakaandre.siftec;

import net.minecraft.commands.CommandSourceStack;
import org.jspecify.annotations.Nullable;

/**
 * A suite's checks that run some ticks later, on its {@link TestQueue}: counted while they wait, each run through
 * {@link TestReport#attempt} (a throw is a FAIL, not a stop), and counted as done in a {@code finally}, so the suite's
 * closing line ("done: ...") always comes, whatever its checks do.
 */
public final class DelayedChecks {
    /** Ticks between two looks at whether every delayed check has run. */
    private static final int FINISH_POLL = 10;

    private final TestQueue queue;
    private final TestReport report;
    private int pending;
    private @Nullable CommandSourceStack source;

    public DelayedChecks(TestQueue queue, TestReport report) {
        this.queue = queue;
        this.report = report;
    }

    /** A new run of the suite, reporting to {@code source}: nothing is waiting. */
    public void start(CommandSourceStack source) {
        this.source = source;
        pending = 0;
    }

    /** Checks still waiting to run. */
    public int pending() {
        return pending;
    }

    /** Runs {@code run} at game time {@code at} or the first tick after (see {@link TestQueue#add}). */
    public void at(long at, Runnable run) {
        pending++;
        CommandSourceStack to = source;
        queue.add(at, () -> {
            try {
                if (to == null) run.run();
                else report.attempt(to, "a delayed check", run);
            } finally {
                pending--;
            }
        });
    }

    /** Runs {@code done} once every delayed check has run (looked at from game time {@code now} on). */
    public void finish(long now, Runnable done) {
        if (pending <= 0) {
            done.run();
            return;
        }
        queue.add(now + FINISH_POLL, () -> finish(now + FINISH_POLL, done));
    }
}
