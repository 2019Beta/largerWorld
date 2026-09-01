package org.devt.largerworld.server;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkGenerationStep;
import net.minecraft.world.chunk.ChunkGenerationSteps;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.GenerationDependencies;
import net.minecraft.world.chunk.Chunk;
import org.devt.largerworld.coordinate.CellPos;
import org.devt.largerworld.coordinate.VirtualChunkPos;
import org.devt.largerworld.mixin.ServerChunkManagerAccessor;
import org.devt.largerworld.world.CellWorldKey;
import org.devt.largerworld.world.CellWorldManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
    private static final Map<MinecraftServer, CellChunkTaskScheduler> SERVER_SCHEDULERS =
            new ConcurrentHashMap<>();
    private static final Map<MinecraftServer,
            ConcurrentHashMap<CellChunkTaskKey, CellChunkTaskScheduler.Priority>>
            SERVER_PRIORITIES = new ConcurrentHashMap<>();

    private CellChunkTaskEngine() {
    }

    public static CompletableFuture<?> requestAccessible(ServerWorld world, ChunkPos localPos) {
        return requestAccessible(
                world, localPos, CellChunkTickets.SHADOW,
                CellChunkTaskScheduler.Priority.INTERACTIVE);
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
            return requestAccessible(
                    world, localPos, CellChunkTickets.PREFETCH,
                    CellChunkTaskScheduler.Priority.PREFETCH);
        });
    }

    public static CompletableFuture<?> requestRegionData(
            ServerWorld world, ChunkPos localPos) {
        return requestRegionData(
                world, localPos, CellChunkTaskScheduler.Priority.INTERACTIVE);
    }

    private static CompletableFuture<?> requestRegionData(
            ServerWorld world,
            ChunkPos localPos,
            CellChunkTaskScheduler.Priority priority) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(localPos, "localPos");
        if (world.getServer().isOnThread()
                && world.getChunkManager().isChunkLoaded(localPos.x, localPos.z)) {
            return CompletableFuture.completedFuture(null);
        }
        CellChunkTaskKey key = CellChunkTaskKey.regionData(world, localPos);
        TaskPool<CellChunkTaskKey> tasks = SERVER_TASKS.computeIfAbsent(
                world.getServer(), ignored -> new TaskPool<>());
        CellChunkTaskScheduler scheduler = scheduler(world.getServer());
        return tasks.request(key, () -> scheduler.submit(
                        key, priority, Set.of(),
                        () -> CellRegionIoPrefetch.prefetch(world, localPos)),
                () -> promoteIfInteractive(scheduler, key, priority));
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
        return startOnServerThread(world.getServer(), () -> {
            world.getChunkManager().addTicket(CellChunkTickets.PREFETCH, localPos, 0);
            return requestStatusNode(
                    world, localPos, status,
                    CellChunkTaskScheduler.Priority.INTERACTIVE);
        });
    }

    private static CompletableFuture<?> requestStatusNode(
            ServerWorld world,
            ChunkPos localPos,
            ChunkStatus status,
            CellChunkTaskScheduler.Priority priority) {
        List<CompletableFuture<?>> dependencies = new ArrayList<>();
        if (status == ChunkStatus.EMPTY) {
            dependencies.add(requestRegionData(world, localPos, priority));
        } else {
            dependencies.add(requestStatusNode(
                    world, localPos, status.getPrevious(), priority));
            addSpatialDependencies(world, localPos, status, priority, dependencies);
        }
        CompletableFuture<?> dependency = CompletableFuture.allOf(
                dependencies.toArray(CompletableFuture[]::new));
        CellChunkTaskKey key = CellChunkTaskKey.status(world, localPos, status);
        rememberPriority(world.getServer(), key, priority);
        TaskPool<CellChunkTaskKey> tasks = SERVER_TASKS.computeIfAbsent(
                world.getServer(), ignored -> new TaskPool<>());
        CellChunkTaskScheduler scheduler = scheduler(world.getServer());
        CompletableFuture<?> result = tasks.request(key, () -> dependency.thenCompose(ignored ->
                        startOnServerThread(world.getServer(), () ->
                                ((ServerChunkManagerAccessor) world.getChunkManager())
                                        .largerworld$getChunkFuture(
                                                localPos.x, localPos.z, status, true))),
                () -> promoteStatus(scheduler, world.getServer(), key, priority));
        result.whenComplete((ignored, error) -> {
            forgetPriority(world.getServer(), key);
            scheduler.forgetPromotion(key);
        });
        return result;
    }

    private static CompletableFuture<?> requestAccessible(
            ServerWorld world,
            ChunkPos localPos,
            ChunkTicketType ticketType,
            CellChunkTaskScheduler.Priority priority) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(localPos, "localPos");
        CellChunkTaskKey key = CellChunkTaskKey.accessible(world, localPos);
        TaskPool<CellChunkTaskKey> tasks = SERVER_TASKS.computeIfAbsent(
                world.getServer(), ignored -> new TaskPool<>());
        CellChunkTaskScheduler scheduler = scheduler(world.getServer());
        CompletableFuture<?> generated = startOnServerThread(world.getServer(), () ->
                requestStatusNode(world, localPos, ChunkStatus.FULL, priority));
        return tasks.request(key, () -> generated.thenCompose(ignored -> scheduler.submit(
                        key, priority, Set.of(),
                        () -> startOnServerThread(world.getServer(), () ->
                                world.getChunkManager().addChunkLoadingTicket(
                                        ticketType, localPos, 0)))),
                () -> promoteIfInteractive(scheduler, key, priority));
    }

    private static void addSpatialDependencies(
            ServerWorld world,
            ChunkPos localPos,
            ChunkStatus status,
            CellChunkTaskScheduler.Priority priority,
            List<CompletableFuture<?>> dependencies) {
        GenerationDependencies spatial = ChunkGenerationSteps.GENERATION
                .get(status).directDependencies();
        for (int distance = 1; distance < spatial.size(); distance++) {
            ChunkStatus required = spatial.get(distance);
            for (int dz = -distance; dz <= distance; dz++) {
                for (int dx = -distance; dx <= distance; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != distance) {
                        continue;
                    }
                    ChunkTarget target = resolve(
                            world, localPos.x + dx, localPos.z + dz);
                    dependencies.add(requestStatusNode(
                            target.world(), target.pos(), required, priority));
                }
            }
        }
    }

    private static Set<CellChunkWriteKey> writeSet(
            ServerWorld world, ChunkPos localPos, ChunkStatus status) {
        ChunkGenerationStep step = ChunkGenerationSteps.GENERATION.get(status);
        int radius = step.blockStateWriteRadius();
        if (radius < 0) {
            return Set.of();
        }
        Set<CellChunkWriteKey> writes = new HashSet<>();
        RegistryKey<World> dimension = CellWorldKey.baseWorld(world.getRegistryKey());
        CellPos sourceCell = CellWorldKey.cell(world.getRegistryKey());
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                VirtualChunkPos target = VirtualChunkPos.fromClient(
                        sourceCell, localPos.x + dx, localPos.z + dz);
                writes.add(new CellChunkWriteKey(
                        dimension, target.cell(), target.localX(), target.localZ()));
            }
        }
        return writes;
    }

    /**
     * Coordinates the real vanilla generation method, regardless of whether it
     * was reached through a Cell view, a normal player ticket, or another mod.
     */
    public static CompletableFuture<Chunk> coordinateGeneration(
            ServerWorld world,
            ChunkPos localPos,
            ChunkGenerationStep step,
            Supplier<CompletableFuture<Chunk>> starter) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(localPos, "localPos");
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(starter, "starter");
        CellChunkTaskKey key = CellChunkTaskKey.status(
                world, localPos, step.targetStatus());
        ConcurrentHashMap<CellChunkTaskKey, CellChunkTaskScheduler.Priority> priorities =
                SERVER_PRIORITIES.get(world.getServer());
        CellChunkTaskScheduler.Priority priority = priorities == null
                ? CellChunkTaskScheduler.Priority.INTERACTIVE
                : priorities.getOrDefault(
                        key, CellChunkTaskScheduler.Priority.INTERACTIVE);
        CompletableFuture<?> scheduled = scheduler(world.getServer()).submit(
                key, priority, writeSet(world, localPos, step.targetStatus()), starter);
        return scheduled.thenApply(Chunk.class::cast);
    }

    private static ChunkTarget resolve(ServerWorld source, int chunkX, int chunkZ) {
        CellPos sourceCell = CellWorldKey.cell(source.getRegistryKey());
        VirtualChunkPos virtual = VirtualChunkPos.fromClient(sourceCell, chunkX, chunkZ);
        ServerWorld target = virtual.cell().equals(sourceCell)
                ? source
                : CellWorldManager.getOrCreate(
                        source.getServer(),
                        CellWorldKey.baseWorld(source.getRegistryKey()),
                        virtual.cell());
        return new ChunkTarget(
                target, new ChunkPos(virtual.localX(), virtual.localZ()));
    }

    private static CellChunkTaskScheduler scheduler(MinecraftServer server) {
        return SERVER_SCHEDULERS.computeIfAbsent(
                server, ignored -> new CellChunkTaskScheduler());
    }

    private static void promoteIfInteractive(
            CellChunkTaskScheduler scheduler,
            CellChunkTaskKey key,
            CellChunkTaskScheduler.Priority priority) {
        if (priority == CellChunkTaskScheduler.Priority.INTERACTIVE) {
            scheduler.promote(key);
        }
    }

    private static void promoteStatus(
            CellChunkTaskScheduler scheduler,
            MinecraftServer server,
            CellChunkTaskKey key,
            CellChunkTaskScheduler.Priority priority) {
        rememberPriority(server, key, priority);
        promoteIfInteractive(scheduler, key, priority);
    }

    private static void rememberPriority(
            MinecraftServer server,
            CellChunkTaskKey key,
            CellChunkTaskScheduler.Priority priority) {
        SERVER_PRIORITIES.computeIfAbsent(server, ignored -> new ConcurrentHashMap<>())
                .merge(key, priority, (left, right) ->
                        left == CellChunkTaskScheduler.Priority.INTERACTIVE
                                || right == CellChunkTaskScheduler.Priority.INTERACTIVE
                                ? CellChunkTaskScheduler.Priority.INTERACTIVE
                                : CellChunkTaskScheduler.Priority.PREFETCH);
    }

    private static void forgetPriority(
            MinecraftServer server, CellChunkTaskKey key) {
        ConcurrentHashMap<CellChunkTaskKey, CellChunkTaskScheduler.Priority> priorities =
                SERVER_PRIORITIES.get(server);
        if (priorities == null) {
            return;
        }
        priorities.remove(key);
        if (priorities.isEmpty()) {
            SERVER_PRIORITIES.remove(server, priorities);
        }
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
        TaskPoolStatistics taskStatistics = tasks == null
                ? TaskPoolStatistics.EMPTY : tasks.statistics();
        CellChunkTaskScheduler taskScheduler = SERVER_SCHEDULERS.get(server);
        CellChunkTaskScheduler.Statistics schedulerStatistics = taskScheduler == null
                ? CellChunkTaskScheduler.Statistics.EMPTY : taskScheduler.statistics();
        return new Statistics(
                taskStatistics.inFlight(),
                taskStatistics.submitted(),
                taskStatistics.coalesced(),
                taskStatistics.completed(),
                taskStatistics.failed(),
                schedulerStatistics.active(),
                schedulerStatistics.queued(),
                schedulerStatistics.queuedPrefetch(),
                schedulerStatistics.rejectedPrefetch(),
                schedulerStatistics.promoted());
    }

    public static void clearServerState(MinecraftServer server) {
        TaskPool<CellChunkTaskKey> tasks = SERVER_TASKS.remove(server);
        if (tasks != null) {
            tasks.clear();
        }
        CellChunkTaskScheduler scheduler = SERVER_SCHEDULERS.remove(server);
        if (scheduler != null) {
            scheduler.clear();
        }
        SERVER_PRIORITIES.remove(server);
    }

    public record Statistics(
            int inFlight,
            long submitted,
            long coalesced,
            long completed,
            long failed,
            int active,
            int queued,
            int queuedPrefetch,
            long rejectedPrefetch,
            long promoted) {
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

    private record ChunkTarget(ServerWorld world, ChunkPos pos) {
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
