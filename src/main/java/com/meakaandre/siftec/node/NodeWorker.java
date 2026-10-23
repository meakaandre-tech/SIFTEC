package com.meakaandre.siftec.node;

import com.meakaandre.siftec.Siftec;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** The node map's worker thread: works cells out ahead and runs long searches off the server thread. */
final class NodeWorker {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "SIFTEC node map");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });

    private NodeWorker() {
    }

    /** Works the cell out on the worker thread, so a later {@link NodeMap#inCell} finds it cached. */
    static void prefetch(ServerLevel level, int cellX, int cellZ) {
        // every chunk of a cell asks, again and again until it is done: queue each cell once
        long pending = NodeMap.cellKey(cellX, cellZ) * 31 + level.dimension().identifier().hashCode();
        if (!PENDING.add(pending)) return;
        WORKER.execute(() -> {
            try {
                NodeMap.inCell(level, cellX, cellZ);
            } catch (RuntimeException e) {
                Siftec.LOGGER.warn("SIFTEC: could not work out node cell {} {}", cellX, cellZ, e);
            } finally {
                PENDING.remove(pending);
            }
        });
    }

    private static final Set<Long> PENDING = ConcurrentHashMap.newKeySet();

    /** Runs a job on the worker thread (a search that combines several lookups), answering on the server thread (null if it failed). */
    static <T> void async(MinecraftServer server, Supplier<T> job, Consumer<@Nullable T> then) {
        WORKER.execute(() -> {
            T result;
            try {
                result = job.get();
            } catch (RuntimeException e) {
                Siftec.LOGGER.warn("SIFTEC: a background search failed", e);
                result = null;
            }
            T done = result;
            server.execute(() -> then.accept(done));
        });
    }

    /**
     * Runs a long job in small pieces on the worker thread: each piece of {@code size} items is its own task, and the
     * next is queued only once it is done, so whatever else waits for the worker (a scanner, the cells of chunks being
     * generated) runs in between. {@code each} gets every piece's answer on the server thread (null if it failed), and
     * {@code done} runs there after the last.
     */
    static <I, R> void batched(MinecraftServer server, List<I> items, int size, Function<List<I>, R> piece,
                               Consumer<@Nullable R> each, Runnable done) {
        List<I> all = List.copyOf(items);
        batchStep(server, all, 0, Math.max(1, size), piece, each, done);
    }

    private static <I, R> void batchStep(MinecraftServer server, List<I> all, int from, int size, Function<List<I>, R> piece,
                                         Consumer<@Nullable R> each, Runnable done) {
        if (from >= all.size()) {
            server.execute(done);
            return;
        }
        WORKER.execute(() -> {
            R result;
            try {
                result = piece.apply(all.subList(from, Math.min(all.size(), from + size)));
            } catch (RuntimeException e) {
                Siftec.LOGGER.warn("SIFTEC: a background search failed", e);
                result = null;
            }
            R answer = result;
            server.execute(() -> each.accept(answer));
            batchStep(server, all, from + size, size, piece, each, done);
        });
    }
}
