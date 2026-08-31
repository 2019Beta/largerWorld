package org.devt.largerworld.server;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * Cell-aware front end for the backing chunk loader.
 *
 * <p>The first request for a global task key starts the vanilla load. Concurrent
 * requests reuse that future, so multiple players looking across the same seam
 * do not independently enqueue the same chunk pipeline. The class deliberately
 * does not depend on ChunkHolder internals, allowing a different backend (for
 * example C2ME) to sit behind it later.</p>
 */
public final class CellChunkTaskEngine {
    private static final Map<MinecraftServer, TaskPool<CellChunkTaskKey>> SERVER_TASKS =
            new ConcurrentHashMap<>();

    private CellChunkTaskEngine() {
    }

    public static CompletableFuture<?> requestAccessible(ServerWorld world, ChunkPos localPos) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(localPos, "localPos");
        CellChunkTaskKey key = CellChunkTaskKey.accessible(world, localPos);
        TaskPool<CellChunkTaskKey> tasks = SERVER_TASKS.computeIfAbsent(
                world.getServer(), ignored -> new TaskPool<>());
        return tasks.request(key, () -> world.getChunkManager()
                .addChunkLoadingTicket(CellChunkTickets.SHADOW, localPos, 0));
    }

    public static Statistics statistics(MinecraftServer server) {
        TaskPool<CellChunkTaskKey> tasks = SERVER_TASKS.get(server);
        return tasks == null ? Statistics.EMPTY : tasks.statistics();
    }

    public static void clearServerState(MinecraftServer server) {
        TaskPool<CellChunkTaskKey> tasks = SERVER_TASKS.remove(server);
        if (tasks != null) {
            tasks.clear();
        }
    }

    public record Statistics(
            int inFlight,
            long submitted,
            long coalesced,
            long completed,
            long failed) {
        private static final Statistics EMPTY = new Statistics(0, 0, 0, 0, 0);
    }

    /** Package-visible for deterministic engine tests without a live ServerWorld. */
    static final class TaskPool<K> {
        private final ConcurrentHashMap<K, CompletableFuture<?>> inFlight =
                new ConcurrentHashMap<>();
        private final LongAdder submitted = new LongAdder();
        private final LongAdder coalesced = new LongAdder();
        private final LongAdder completed = new LongAdder();
        private final LongAdder failed = new LongAdder();

        CompletableFuture<?> request(K key, Supplier<? extends CompletableFuture<?>> starter) {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(starter, "starter");
            CompletableFuture<Object> task = new CompletableFuture<>();
            CompletableFuture<?> existing = inFlight.putIfAbsent(key, task);
            if (existing != null) {
                coalesced.increment();
                return existing;
            }

            submitted.increment();
            task.whenComplete((result, error) -> {
                if (!inFlight.remove(key, task)) {
                    return;
                }
                if (error == null) {
                    completed.increment();
                } else {
                    failed.increment();
                }
            });
            try {
                CompletableFuture<?> backend = Objects.requireNonNull(
                        starter.get(), "starter returned null");
                backend.whenComplete((result, error) -> {
                    if (error == null) {
                        task.complete(result);
                    } else {
                        task.completeExceptionally(error);
                    }
                });
            } catch (Throwable error) {
                task.completeExceptionally(error);
            }
            return task;
        }

        Statistics statistics() {
            return new Statistics(
                    inFlight.size(),
                    submitted.sum(),
                    coalesced.sum(),
                    completed.sum(),
                    failed.sum());
        }

        void clear() {
            inFlight.clear();
        }
    }
}
