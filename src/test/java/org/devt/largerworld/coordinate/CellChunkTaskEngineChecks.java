package org.devt.largerworld.server;

import net.minecraft.world.chunk.ChunkStatus;
import org.devt.largerworld.coordinate.VirtualPosition;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/** Deterministic checks for in-flight task coalescing and cleanup. */
public final class CellChunkTaskEngineChecks {
    private CellChunkTaskEngineChecks() {
    }

    public static void run() {
        coalescesEqualInFlightTasks();
        separatesDifferentTaskKeys();
        removesFailedTasksForRetry();
        detectsWhetherAViewCanReachASeam();
        mapsChunkStatusNodes();
        predictsFastApproachesBeforeTheViewTouchesTheSeam();
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

    private static void mapsChunkStatusNodes() {
        check(CellChunkTaskKey.Target.from(ChunkStatus.EMPTY)
                        == CellChunkTaskKey.Target.EMPTY,
                "empty status has an explicit task node");
        check(CellChunkTaskKey.Target.from(ChunkStatus.FEATURES)
                        == CellChunkTaskKey.Target.FEATURES,
                "features status has an explicit task node");
        check(CellChunkTaskKey.Target.from(ChunkStatus.FULL)
                        == CellChunkTaskKey.Target.FULL,
                "full status has an explicit task node");
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

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
