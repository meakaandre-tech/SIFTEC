package com.meakaandre.siftec;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Work a selftest suite puts off: each entry runs at the end of the first server tick at which the overworld's game
 * time has reached the entry's time. A suite keeps one queue and hooks it up where it registers its other listeners,
 * so the listeners keep their order among the mod's own: {@link #register} for a plain queue, or its own listener
 * calling {@link #runDue} and {@link #clear} when the suite does more on a tick or on a stop.
 */
public final class TestQueue {
    private record Later(long at, Runnable run) {
    }

    private final List<Later> entries = new ArrayList<>();

    /** Runs {@code run} at the end of the first server tick at which the overworld's game time is {@code at} or later. */
    public void add(long at, Runnable run) {
        entries.add(new Later(at, run));
    }

    /** Registers the plain listeners: due entries run at the end of every server tick, and a stopped server drops all. */
    public void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> runDue(server.overworld().getGameTime()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> clear());
    }

    /** Runs every entry due by {@code now}; see {@link #runDue(long, Consumer)}. */
    public void runDue(long now) {
        runDue(now, Runnable::run);
    }

    /**
     * Runs every entry due by {@code now} through {@code runner}, in the order they were added. Each is taken off the
     * queue before it runs; entries added while these run wait for a later tick.
     */
    public void runDue(long now, Consumer<Runnable> runner) {
        for (Later l : new ArrayList<>(entries)) {
            if (l.at > now) continue;
            entries.remove(l);
            runner.accept(l.run);
        }
    }

    /** Drops every entry. */
    public void clear() {
        entries.clear();
    }
}
