package org.devt.largerworld.server;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkStatus;

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
        return requestAccessible(world, localPos, CellChunkTickets.SHADOW);
    }

    /** Starts speculative Region IO and an expiring accessible-chunk request. */
    public static CompletableFuture<?> prefetchAccessible(
            ServerWorld world, ChunkPos localPos) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(localPos, "localPos");
        return startOnServerThread(world.getServer(), () -> {
            world.getChunkManager().addTicket(CellChunkTickets.PREFETCH, localPos, 0);
            if (world.getChunkManager().isChunkLoaded(localPos.x, localPos.z)) {
                return CompletableFuture.completedFuture(null);
            }
            return requestAccessible(world, localPos, CellChunkTickets.PREFETCH);
        });
    }

    public static CompletableFuture<?> requestRegionData(
            ServerWorld world, ChunkPos localPos) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(localPos, "localPos");
        if (world.getServer().isOnThread()
                && world.getChunkManager().isChunkLoaded(localPos.x, localPos.z)) {
            return CompletableFuture.completedFuture(null);
        }
        CellChunkTaskKey key = CellChunkTaskKey.regionData(world, localPos);
        TaskPool<CellChunkTaskKey> tasks = SERVER_TASKS.computeIfAbsent(
                world.getServer(), ignored -> new TaskPool<>());
        return tasks.request(key, () -> CellRegionIoPrefetch.prefetch(world, localPos));
    }

    /**
     * Requests one explicit ChunkStatus node. Nodes for the same chunk are
     * chained through {@link ChunkStatus#getPrevious()}, while different
     * chunks/cells remain independently schedulable.
     */
    public static CompletableFuture<?> requestStatus(
            ServerWorld world, ChunkPos localPos, ChunkStatus status) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(localPos, "localPos");
        Objects.requireNonNull(status, "status");
        CompletableFuture<?> ticketReady = startOnServerThread(world.getServer(), () -> {
            world.getChunkManager().addTicket(CellChunkTickets.PREFETCH, localPos, 0);
            return CompletableFuture.completedFuture(null);
        });
        return ticketReady.thenCompose(ignored -> requestStatusNode(world, localPos, status));
    }

    private static CompletableFuture<?> requestStatusNode(
            ServerWorld world, ChunkPos localPos, ChunkStatus status) {
        CompletableFuture<?> dependency = status == ChunkStatus.EMPTY
                ? requestRegionData(world, localPos)
                : requestStatusNode(world, localPos, status.getPrevious());
        CellChunkTaskKey key = CellChunkTaskKey.status(world, localPos, status);
        TaskPool<CellChunkTaskKey> tasks = SERVER_TASKS.computeIfAbsent(
                world.getServer(), ignored -> new TaskPool<>());
        return tasks.request(key, () -> dependency.thenCompose(ignored ->
                startOnServerThread(world.getServer(), () ->
                        world.getChunkManager().getChunkFutureSyncOnMainThread(
                                localPos.x, localPos.z, status, true))));
    }

    private static CompletableFuture<?> requestAccessible(
            ServerWorld world,
            ChunkPos localPos,
            ChunkTicketType ticketType) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(localPos, "localPos");
        // Publish the asynchronous RegionFile read before the ticket causes the
        // vanilla loader to ask for NBT. The mixin hook then consumes this exact
        // future, so disk IO overlaps ticket propagation without a duplicate read.
        requestRegionData(world, localPos);
        CellChunkTaskKey key = CellChunkTaskKey.accessible(world, localPos);
        TaskPool<CellChunkTaskKey> tasks = SERVER_TASKS.computeIfAbsent(
                world.getServer(), ignored -> new TaskPool<>());
        return tasks.request(key, () -> startOnServerThread(world.getServer(), () ->
                world.getChunkManager().addChunkLoadingTicket(ticketType, localPos, 0)));
    }

    private static CompletableFuture<?> startOnServerThread(
            MinecraftServer server,
            Supplier<? extends CompletableFuture<?>> starter) {
        if (server.isOnThread()) {
            return starter.get();
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
