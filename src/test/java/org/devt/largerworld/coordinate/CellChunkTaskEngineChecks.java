package org.devt.largerworld.server;

import org.devt.largerworld.coordinate.VirtualPosition;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/** Deterministic checks for in-flight task coalescing and cleanup. */
public final class CellChunkTaskEngineChecks {
    private CellChunkTaskEngineChecks() {
    }

    public static void run() {
        coalescesEqualInFlightTasks();
        coalescesReentrantRequestsBeforeStarterReturns();
        separatesDifferentTaskKeys();
        removesFailedTasksForRetry();
        detectsWhetherAViewCanReachASeam();
        predictsFastApproachesBeforeTheViewTouchesTheSeam();
        coalescesChunkWritesAndDefersSerialization();
        retriesFailedChunkWrites();
    }

    private static void coalescesReentrantRequestsBeforeStarterReturns() {
        CellChunkTaskEngine.TaskPool<String> pool = new CellChunkTaskEngine.TaskPool<>();
        CompletableFuture<Void> pending = new CompletableFuture<>();
        java.util.concurrent.atomic.AtomicReference<CompletableFuture<?>> reentrant =
                new java.util.concurrent.atomic.AtomicReference<>();
        AtomicInteger nestedStarts = new AtomicInteger();

        CompletableFuture<?> first = pool.request("recursive", () -> {
            reentrant.set(pool.request("recursive", () -> {
                nestedStarts.incrementAndGet();
                return new CompletableFuture<>();
            }));
            return pending;
        });

        check(reentrant.get() == first,
                "recursive dependency construction observes the installed placeholder");
        check(nestedStarts.get() == 0,
                "recursive dependency construction does not start the same key twice");
        pending.complete(null);
    }

    private static void coalescesEqualInFlightTasks() {
        CellChunkTaskEngine.TaskPool<String> pool = new CellChunkTaskEngine.TaskPool<>();
        CompletableFuture<String> pending = new CompletableFuture<>();
        AtomicInteger starts = new AtomicInteger();

        CompletableFuture<?> first = pool.request("same", () -> {
            starts.incrementAndGet();
            return pending;
        });
        CompletableFuture<?> second = pool.request("same", () -> {
            starts.incrementAndGet();
            return new CompletableFuture<>();
        });

        check(first == second, "equal in-flight keys share one future");
        check(starts.get() == 1, "equal in-flight keys start once");
        check(pool.statistics().inFlight() == 1, "one task remains in flight");
        check(pool.statistics().coalesced() == 1, "coalesced request is counted");

        pending.complete("ready");
        check(pool.statistics().inFlight() == 0, "completed task is removed");
        check(pool.statistics().completed() == 1, "completion is counted");

        pool.request("same", CompletableFuture::new);
        check(starts.get() == 1, "retry uses the new starter rather than the old one");
        check(pool.statistics().submitted() == 2, "completed key can be submitted again");
    }

    private static void separatesDifferentTaskKeys() {
        CellChunkTaskEngine.TaskPool<String> pool = new CellChunkTaskEngine.TaskPool<>();
        CompletableFuture<?> first = pool.request("cell-a", CompletableFuture::new);
        CompletableFuture<?> second = pool.request("cell-b", CompletableFuture::new);
        check(first != second, "different global task keys stay independent");
        check(pool.statistics().inFlight() == 2, "different keys run concurrently");
    }

    private static void removesFailedTasksForRetry() {
        CellChunkTaskEngine.TaskPool<String> pool = new CellChunkTaskEngine.TaskPool<>();
        CompletableFuture<Void> failed = new CompletableFuture<>();
        CompletableFuture<?> first = pool.request("retry", () -> failed);
        failed.completeExceptionally(new IllegalStateException("expected test failure"));
        check(pool.statistics().inFlight() == 0, "failed task is removed");
        check(pool.statistics().failed() == 1, "failure is counted");

        CompletableFuture<?> retry = pool.request("retry", CompletableFuture::new);
        check(retry != first, "failed task can be retried");
    }

    private static void detectsWhetherAViewCanReachASeam() {
        check(CellViewTracker.viewStaysInsideCell(0, 0, 32),
                "ordinary views use the allocation-free path");
        check(CellViewTracker.viewStaysInsideCell(32756, 0, 10),
                "view ending one chunk before a seam stays local");
        check(!CellViewTracker.viewStaysInsideCell(32757, 0, 10),
                "view touching the positive seam is scanned");
        check(!CellViewTracker.viewStaysInsideCell(-32758, 0, 10),
                "view touching the negative seam is scanned");
    }

    private static void predictsFastApproachesBeforeTheViewTouchesTheSeam() {
        CellPrefetchPlanner.Prediction idle = CellPrefetchPlanner.predict(
                0.0, 0.0, 0.0, 0.0, 10, 60);
        check(!idle.crossesCell(), "stationary player far from a seam is ignored");

        CellPrefetchPlanner.Prediction east = CellPrefetchPlanner.predict(
                VirtualPosition.HALF_CELL - 1_000.0,
                25.0,
                20.0,
                0.0,
                10,
                60);
        check(east.deltaX() == 1 && east.deltaZ() == 0,
                "velocity predicts an east crossing before view overlap");
        check(east.entryLocalX() == -VirtualPosition.HALF_CELL + 1.0,
                "east prediction maps to the target cell's west edge");

        CellPrefetchPlanner.Prediction away = CellPrefetchPlanner.predict(
                VirtualPosition.HALF_CELL - 1_000.0,
                0.0,
                -20.0,
                0.0,
                10,
                60);
        check(!away.crossesCell(), "motion away from a distant seam is ignored");

        CellPrefetchPlanner.Prediction diagonal = CellPrefetchPlanner.predict(
                VirtualPosition.HALF_CELL - 500.0,
                -VirtualPosition.HALF_CELL + 500.0,
                10.0,
                -10.0,
                10,
                60);
        check(diagonal.deltaX() == 1 && diagonal.deltaZ() == -1,
                "diagonal motion prepares the diagonal target cell");
    }

    private static void coalescesChunkWritesAndDefersSerialization() {
        CellChunkIoQueue.CoalescingWriteQueue<String, Integer> queue =
                new CellChunkIoQueue.CoalescingWriteQueue<>(1, 0L);
        java.util.List<Integer> written = new java.util.ArrayList<>();
        java.util.List<CompletableFuture<Void>> backends = new java.util.ArrayList<>();
        java.util.function.Function<java.util.function.Supplier<Integer>,
                CompletableFuture<Void>> writer = value -> {
            written.add(value.get());
            CompletableFuture<Void> backend = new CompletableFuture<>();
            backends.add(backend);
            return backend;
        };

        CompletableFuture<Void> first = queue.enqueue("chunk", () -> 1, writer);
        CompletableFuture<Void> second = queue.enqueue("chunk", () -> 2, writer);
        CompletableFuture<Void> latest = queue.enqueue("chunk", () -> 3, writer);
        CompletableFuture<Void> barrier = queue.barrier("chunk");
        check(written.equals(java.util.List.of(1)),
                "only the active chunk snapshot is serialized immediately");
        check(!barrier.isDone(), "write barrier waits for active and pending snapshots");

        backends.get(0).complete(null);
        check(first.isDone(), "active write acknowledgement completes after persistence");
        check(written.equals(java.util.List.of(1, 3)),
                "multiple pending saves collapse to their latest snapshot");
        check(!second.isDone() && !latest.isDone(),
                "superseded callers wait for the latest snapshot");
        backends.get(1).complete(null);
        check(second.isDone() && latest.isDone() && barrier.isDone(),
                "latest persistence releases all coalesced callers and the barrier");

        AtomicInteger serializations = new AtomicInteger();
        CompletableFuture<Integer> lazy = CellChunkIoQueue.lazySerialize(
                serializations::incrementAndGet, Runnable::run);
        check(serializations.get() == 0, "NBT serialization remains lazy before consumption");
        check(lazy.join() == 1 && serializations.get() == 1,
                "lazy NBT serialization runs exactly once when consumed");
    }

    private static void retriesFailedChunkWrites() {
        CellChunkIoQueue.CoalescingWriteQueue<String, Integer> queue =
                new CellChunkIoQueue.CoalescingWriteQueue<>(3, 0L);
        AtomicInteger attempts = new AtomicInteger();
        CompletableFuture<Void> saved = queue.enqueue("retry", () -> 7, value -> {
            int attempt = attempts.incrementAndGet();
            if (attempt < 3) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("expected write failure"));
            }
            check(value.get() == 7, "retry reuses the selected serialized snapshot");
            return CompletableFuture.completedFuture(null);
        });
        saved.join();
        check(attempts.get() == 3, "failed chunk writes retry to the configured limit");
        check(queue.barrier().isDone(), "successful retry drains the manager barrier");

        CellChunkIoQueue.CoalescingWriteQueue<String, Integer> exhausted =
                new CellChunkIoQueue.CoalescingWriteQueue<>(1, 0L);
        CompletableFuture<Void> failed = exhausted.enqueue(
                "failed", () -> 9,
                value -> CompletableFuture.failedFuture(
                        new IllegalStateException("final expected write failure")));
        boolean reported = false;
        try {
            failed.join();
        } catch (java.util.concurrent.CompletionException expected) {
            reported = true;
        }
        check(reported, "final write failure reaches the vanilla acknowledgement");
        check(exhausted.barrier().isDone(),
                "final write failure still releases the unload barrier");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
