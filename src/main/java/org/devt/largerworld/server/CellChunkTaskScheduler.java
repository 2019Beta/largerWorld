package org.devt.largerworld.server;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Bounded admission queue for one server's Cell-aware chunk graph.
 *
 * <p>Admission and write-set ownership are decided together. A task waiting for
 * an overlapping write set therefore consumes no active slot, and no Java lock
 * is held across an asynchronous chunk-generation future.</p>
 */
final class CellChunkTaskScheduler {
    private static final int DEFAULT_MAX_ACTIVE = Math.max(1,
            Runtime.getRuntime().availableProcessors());
    private static final int CONFIGURED_MAX_ACTIVE = Math.max(1,
            Integer.getInteger("largerworld.chunkTasks.maxActive", DEFAULT_MAX_ACTIVE));
    private static final int CONFIGURED_MAX_QUEUED_PREFETCH = Math.max(0,
            Integer.getInteger("largerworld.chunkTasks.maxQueuedPrefetch", 512));

    private final PriorityQueue<Job> pending = new PriorityQueue<>(
            Comparator.comparingInt((Job job) -> job.priority.rank)
                    .thenComparingLong(job -> job.sequence));
    private final Map<CellChunkTaskKey, Job> pendingByKey = new HashMap<>();
    private final Set<CellChunkTaskKey> activeKeys = new HashSet<>();
    private final Set<CellChunkTaskKey> requestedPromotions = new HashSet<>();
    private final Set<CellChunkWriteKey> activeWrites = new HashSet<>();
    private final AtomicLong sequences = new AtomicLong();
    private final int maxActive;
    private final int maxQueuedPrefetch;
    private int active;
    private int queuedPrefetch;
    private long rejectedPrefetch;
    private long promoted;

    CellChunkTaskScheduler() {
        this(CONFIGURED_MAX_ACTIVE, CONFIGURED_MAX_QUEUED_PREFETCH);
    }

    /** Package-visible for deterministic scheduler tests. */
    CellChunkTaskScheduler(int maxActive, int maxQueuedPrefetch) {
        if (maxActive < 1 || maxQueuedPrefetch < 0) {
            throw new IllegalArgumentException("Invalid chunk scheduler limits");
        }
        this.maxActive = maxActive;
        this.maxQueuedPrefetch = maxQueuedPrefetch;
    }

    CompletableFuture<?> submit(
            CellChunkTaskKey key,
            Priority priority,
            Set<CellChunkWriteKey> writes,
            Supplier<? extends CompletableFuture<?>> starter) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(writes, "writes");
        Objects.requireNonNull(starter, "starter");
        CompletableFuture<Object> result = new CompletableFuture<>();
        synchronized (this) {
            if (requestedPromotions.remove(key)) {
                priority = Priority.INTERACTIVE;
            }
            if (priority == Priority.PREFETCH
                    && queuedPrefetch >= maxQueuedPrefetch) {
                rejectedPrefetch++;
                result.completeExceptionally(new RejectedExecutionException(
                        "Larger World speculative chunk queue is full"));
                return result;
            }
            Job job = new Job(
                    key, priority, Set.copyOf(writes), starter, result,
                    sequences.getAndIncrement());
            pending.add(job);
            pendingByKey.put(key, job);
            if (priority == Priority.PREFETCH) {
                queuedPrefetch++;
            }
        }
        drain();
        return result;
    }

    void promote(CellChunkTaskKey key) {
        synchronized (this) {
            Job job = pendingByKey.get(key);
            if (activeKeys.contains(key)) {
                return;
            }
            if (job == null) {
                requestedPromotions.add(key);
                return;
            }
            if (job.priority == Priority.INTERACTIVE) {
                return;
            }
            pending.remove(job);
            job.priority = Priority.INTERACTIVE;
            queuedPrefetch--;
            promoted++;
            pending.add(job);
        }
        drain();
    }

    synchronized void forgetPromotion(CellChunkTaskKey key) {
        requestedPromotions.remove(key);
    }

    synchronized Statistics statistics() {
        return new Statistics(
                active, pending.size(), queuedPrefetch, rejectedPrefetch, promoted);
    }

    void clear() {
        List<Job> abandoned;
        synchronized (this) {
            abandoned = new ArrayList<>(pending);
            pending.clear();
            pendingByKey.clear();
            activeKeys.clear();
            requestedPromotions.clear();
            activeWrites.clear();
            queuedPrefetch = 0;
        }
        RejectedExecutionException error = new RejectedExecutionException(
                "Server chunk scheduler was closed");
        abandoned.forEach(job -> job.result.completeExceptionally(error));
    }

    private void drain() {
        List<Job> ready = new ArrayList<>();
        synchronized (this) {
            while (active < maxActive) {
                Job selected = firstRunnable();
                if (selected == null) {
                    break;
                }
                pending.remove(selected);
                pendingByKey.remove(selected.key, selected);
                if (selected.priority == Priority.PREFETCH) {
                    queuedPrefetch--;
                }
                active++;
                activeKeys.add(selected.key);
                activeWrites.addAll(selected.writes);
                ready.add(selected);
            }
        }
        ready.forEach(this::start);
    }

    private Job firstRunnable() {
        return pending.stream()
                .sorted(pending.comparator())
                .filter(job -> job.writes.stream().noneMatch(activeWrites::contains))
                .findFirst()
                .orElse(null);
    }

    private void start(Job job) {
        try {
            CompletableFuture<?> backend = Objects.requireNonNull(
                    job.starter.get(), "starter returned null");
            backend.whenComplete((value, error) -> finish(job, value, error));
        } catch (Throwable error) {
            finish(job, null, error);
        }
    }

    private void finish(Job job, Object value, Throwable error) {
        synchronized (this) {
            active--;
            activeKeys.remove(job.key);
            activeWrites.removeAll(job.writes);
        }
        if (error == null) {
            job.result.complete(value);
        } else {
            job.result.completeExceptionally(error);
        }
        drain();
    }

    enum Priority {
        INTERACTIVE(0),
        PREFETCH(1);

        private final int rank;

        Priority(int rank) {
            this.rank = rank;
        }
    }

    record Statistics(
            int active,
            int queued,
            int queuedPrefetch,
            long rejectedPrefetch,
            long promoted) {
        static final Statistics EMPTY = new Statistics(0, 0, 0, 0, 0);
    }

    private static final class Job {
        private final CellChunkTaskKey key;
        private Priority priority;
        private final Set<CellChunkWriteKey> writes;
        private final Supplier<? extends CompletableFuture<?>> starter;
        private final CompletableFuture<Object> result;
        private final long sequence;

        private Job(
                CellChunkTaskKey key,
                Priority priority,
                Set<CellChunkWriteKey> writes,
                Supplier<? extends CompletableFuture<?>> starter,
                CompletableFuture<Object> result,
                long sequence) {
            this.key = key;
            this.priority = priority;
            this.writes = writes;
            this.starter = starter;
            this.result = result;
            this.sequence = sequence;
        }
    }
}
