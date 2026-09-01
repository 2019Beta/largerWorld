package org.devt.largerworld.server;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * Coalesces Cell chunk requests while leaving dependency expansion, generation
 * ordering and thread ownership entirely to vanilla's chunk manager.
 *
 * <p>Earlier versions rebuilt the ChunkStatus graph and wrapped every vanilla
 * generation step in a second scheduler. That changed when ChunkLevelManager
 * and ChunkHolder state was mutated and could corrupt vanilla's pending-update
 * collections. A Cell only needs a globally unique request key; the future
 * returned by addChunkLoadingTicket is the authoritative completion signal.</p>
 */
public final class CellChunkTaskEngine {
    private static final Map<MinecraftServer, TaskPool<CellChunkTaskKey>> SERVER_TASKS =
            new ConcurrentHashMap<>();

    private CellChunkTaskEngine() {
    }

    public static CompletableFuture<?> requestAccessible(
            ServerWorld world, ChunkPos localPos) {
        return requestAccessible(world, localPos, CellChunkTickets.SHADOW);
    }

    /** Starts an expiring vanilla accessible-chunk request for prediction. */
    public static CompletableFuture<?> prefetchAccessible(
            ServerWorld world, ChunkPos localPos) {
        return requestAccessible(world, localPos, CellChunkTickets.PREFETCH);
    }

    /** Prepares the landing chunk using vanilla's authoritative future. */
    public static CompletableFuture<?> prepareAccessible(
            ServerWorld world, ChunkPos localPos) {
        return requestAccessible(world, localPos, CellChunkTickets.PREFETCH);
    }

    private static CompletableFuture<?> requestAccessible(
            ServerWorld world,
            ChunkPos localPos,
            ChunkTicketType ticketType) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(localPos, "localPos");
        Objects.requireNonNull(ticketType, "ticketType");
        CellChunkTaskKey key = CellChunkTaskKey.accessible(world, localPos);
        TaskPool<CellChunkTaskKey> tasks = SERVER_TASKS.computeIfAbsent(
                world.getServer(), ignored -> new TaskPool<>());
        return tasks.request(key, () -> startOnServerThread(
                world.getServer(), () -> world.getChunkManager().addChunkLoadingTicket(
                        ticketType, localPos, 0)));
    }

    private static CompletableFuture<?> startOnServerThread(
            MinecraftServer server,
            Supplier<? extends CompletableFuture<?>> starter) {
        if (server.isOnThread()) {
            return Objects.requireNonNull(starter.get(), "starter returned null");
        }
        CompletableFuture<Object> scheduled = new CompletableFuture<>();
        server.execute(() -> {
            try {
                CompletableFuture<?> backend = Objects.requireNonNull(
                        starter.get(), "starter returned null");
                backend.whenComplete((result, error) -> {
                    if (error == null) {
                        scheduled.complete(result);
                    } else {
                        scheduled.completeExceptionally(error);
                    }
                });
            } catch (Throwable error) {
                scheduled.completeExceptionally(error);
            }
        });
        return scheduled;
    }

    public static Statistics statistics(MinecraftServer server) {
        TaskPool<CellChunkTaskKey> tasks = SERVER_TASKS.get(server);
        TaskPoolStatistics taskStatistics = tasks == null
                ? TaskPoolStatistics.EMPTY : tasks.statistics();
        return new Statistics(
                taskStatistics.inFlight(), taskStatistics.submitted(),
                taskStatistics.coalesced(), taskStatistics.completed(),
                taskStatistics.failed());
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
    }

    record TaskPoolStatistics(
            int inFlight,
            long submitted,
            long coalesced,
            long completed,
            long failed) {
        private static final TaskPoolStatistics EMPTY =
                new TaskPoolStatistics(0, 0, 0, 0, 0);
    }

    /** Package-visible for deterministic coalescing tests. */
    static final class TaskPool<K> {
        private final ConcurrentHashMap<K, CompletableFuture<?>> inFlight =
                new ConcurrentHashMap<>();
        private final LongAdder submitted = new LongAdder();
        private final LongAdder coalesced = new LongAdder();
        private final LongAdder completed = new LongAdder();
        private final LongAdder failed = new LongAdder();

        CompletableFuture<?> request(K key, Supplier<? extends CompletableFuture<?>> starter) {
            return request(key, starter, () -> { });
        }

        CompletableFuture<?> request(
                K key,
                Supplier<? extends CompletableFuture<?>> starter,
                Runnable onCoalesced) {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(starter, "starter");
            Objects.requireNonNull(onCoalesced, "onCoalesced");
            CompletableFuture<Object> task = new CompletableFuture<>();
            CompletableFuture<?> existing = inFlight.putIfAbsent(key, task);
            if (existing != null) {
                coalesced.increment();
                onCoalesced.run();
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

        TaskPoolStatistics statistics() {
            return new TaskPoolStatistics(
                    inFlight.size(), submitted.sum(), coalesced.sum(),
                    completed.sum(), failed.sum());
        }

        void clear() {
            inFlight.clear();
        }
    }
}
