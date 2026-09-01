package org.devt.largerworld.server;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerChunkLoadingManager;
import net.minecraft.util.math.ChunkPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.Consumer;

/**
 * Coalesces asynchronous chunk writes before they reach StorageIoWorker.
 *
 * <p>At most one write and one replaceable pending snapshot exist per chunk.
 * Every caller still receives a future that completes only after its snapshot,
 * or a newer snapshot that superseded it, has reached the backing writer.</p>
 */
public final class CellChunkIoQueue {
    private static final int MAX_ATTEMPTS = Math.max(1,
            Integer.getInteger("largerworld.chunkIo.maxWriteAttempts", 3));
    private static final long RETRY_DELAY_MILLIS = Math.max(0L,
            Long.getLong("largerworld.chunkIo.retryDelayMillis", 25L));
    private static final Map<ServerChunkLoadingManager,
            CoalescingWriteQueue<Long, NbtCompound>> WRITES = new HashMap<>();
    private static final LongAdder SUBMITTED = new LongAdder();
    private static final LongAdder COALESCED = new LongAdder();
    private static final LongAdder RETRIED = new LongAdder();
    private static final LongAdder COMPLETED = new LongAdder();
    private static final LongAdder FAILED = new LongAdder();
    private static final LongAdder DEFERRED_SERIALIZATIONS = new LongAdder();

    private CellChunkIoQueue() {
    }

    public static CompletableFuture<Void> enqueue(
            ServerChunkLoadingManager manager,
            ChunkPos pos,
            Supplier<NbtCompound> nbt,
            Function<Supplier<NbtCompound>, CompletableFuture<Void>> writer) {
        Objects.requireNonNull(manager, "manager");
        Objects.requireNonNull(pos, "pos");
        synchronized (WRITES) {
            return WRITES.computeIfAbsent(manager, ignored ->
                            new CoalescingWriteQueue<>(
                                    MAX_ATTEMPTS,
                                    RETRY_DELAY_MILLIS,
                                    queue -> {
                                        synchronized (WRITES) {
                                            if (queue.size() == 0) {
                                                WRITES.remove(manager, queue);
                                            }
                                        }
                                    }))
                    .enqueue(pos.toLong(), nbt, writer);
        }
    }

    /** Completes normally when all writes currently associated with this chunk drain. */
    public static CompletableFuture<Void> barrier(
            ServerChunkLoadingManager manager, ChunkPos pos) {
        synchronized (WRITES) {
            CoalescingWriteQueue<Long, NbtCompound> queue = WRITES.get(manager);
            return queue == null
                    ? CompletableFuture.completedFuture(null)
                    : queue.barrier(pos.toLong());
        }
    }

    /** Completes normally when the manager has no active or pending writes. */
    public static CompletableFuture<Void> barrier(ServerChunkLoadingManager manager) {
        synchronized (WRITES) {
            CoalescingWriteQueue<Long, NbtCompound> queue = WRITES.get(manager);
            return queue == null
                    ? CompletableFuture.completedFuture(null)
                    : queue.barrier();
        }
    }

    /** Defers the allocation-heavy NBT conversion until StorageIoWorker needs it. */
    public static <T> CompletableFuture<T> lazySerialize(
            Supplier<T> serializer, Executor executor) {
        DEFERRED_SERIALIZATIONS.increment();
        return new LazyFuture<>(serializer, executor);
    }

    public static Statistics statistics() {
        int chunks;
        synchronized (WRITES) {
            chunks = WRITES.values().stream().mapToInt(CoalescingWriteQueue::size).sum();
        }
        return new Statistics(
                chunks,
                SUBMITTED.sum(),
                COALESCED.sum(),
                RETRIED.sum(),
                COMPLETED.sum(),
                FAILED.sum(),
                DEFERRED_SERIALIZATIONS.sum());
    }

    public static void clearServerState() {
        synchronized (WRITES) {
            WRITES.clear();
        }
        SUBMITTED.reset();
        COALESCED.reset();
        RETRIED.reset();
        COMPLETED.reset();
        FAILED.reset();
        DEFERRED_SERIALIZATIONS.reset();
    }

    public record Statistics(
            int chunks,
            long submitted,
            long coalesced,
            long retried,
            long completed,
            long failed,
            long deferredSerializations) {
    }

    /** Package-visible deterministic core used by the engine checks. */
    static final class CoalescingWriteQueue<K, V> {
        private final Map<K, Slot<K, V>> slots = new HashMap<>();
        private final int maxAttempts;
        private final long retryDelayMillis;
        private final Consumer<CoalescingWriteQueue<K, V>> onIdle;

        CoalescingWriteQueue(int maxAttempts, long retryDelayMillis) {
            this(maxAttempts, retryDelayMillis, ignored -> { });
        }

        private CoalescingWriteQueue(
                int maxAttempts,
                long retryDelayMillis,
                Consumer<CoalescingWriteQueue<K, V>> onIdle) {
            if (maxAttempts < 1 || retryDelayMillis < 0L) {
                throw new IllegalArgumentException("Invalid chunk IO retry policy");
            }
            this.maxAttempts = maxAttempts;
            this.retryDelayMillis = retryDelayMillis;
            this.onIdle = Objects.requireNonNull(onIdle, "onIdle");
        }

        CompletableFuture<Void> enqueue(
                K key,
                Supplier<V> value,
                Function<Supplier<V>, CompletableFuture<Void>> writer) {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(writer, "writer");
            CompletableFuture<Void> acknowledgement = new CompletableFuture<>();
            Work<K, V> start = null;
            synchronized (this) {
                Slot<K, V> slot = slots.get(key);
                if (slot == null) {
                    slot = new Slot<>(new CompletableFuture<>());
                    slots.put(key, slot);
                    start = new Work<>(
                            key, value, writer,
                            new ArrayList<>(List.of(acknowledgement)), slot);
                    slot.active = start;
                    SUBMITTED.increment();
                } else {
                    if (slot.pending == null) {
                        slot.pending = new Work<>(
                                key, value, writer,
                                new ArrayList<>(List.of(acknowledgement)), slot);
                    } else {
                        slot.pending.value = value;
                        slot.pending.writer = writer;
                        slot.pending.acknowledgements.add(acknowledgement);
                    }
                    COALESCED.increment();
                }
            }
            if (start != null) {
                start(start, 1);
            }
            return acknowledgement;
        }

        synchronized CompletableFuture<Void> barrier(K key) {
            Slot<K, V> slot = slots.get(key);
            return slot == null
                    ? CompletableFuture.completedFuture(null)
                    : slot.drained;
        }

        synchronized CompletableFuture<Void> barrier() {
            if (slots.isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }
            return CompletableFuture.allOf(slots.values().stream()
                    .map(slot -> slot.drained)
                    .toArray(CompletableFuture[]::new));
        }

        synchronized int size() {
            return slots.size();
        }

        private void start(Work<K, V> work, int attempt) {
            CompletableFuture<Void> backend;
            try {
                backend = Objects.requireNonNull(
                        work.writer.apply(work.value), "writer returned null");
            } catch (Throwable error) {
                retryOrFinish(work, attempt, error);
                return;
            }
            backend.whenComplete((ignored, error) -> {
                if (error == null) {
                    COMPLETED.increment();
                    work.acknowledgements.forEach(future -> future.complete(null));
                    advance(work);
                } else {
                    retryOrFinish(work, attempt, error);
                }
            });
        }

        private void retryOrFinish(Work<K, V> work, int attempt, Throwable error) {
            if (attempt < maxAttempts) {
                RETRIED.increment();
                CompletableFuture.runAsync(
                        () -> start(work, attempt + 1),
                        CompletableFuture.delayedExecutor(
                                retryDelayMillis, TimeUnit.MILLISECONDS));
                return;
            }
            FAILED.increment();
            work.acknowledgements.forEach(future -> future.completeExceptionally(error));
            advance(work);
        }

        private void advance(Work<K, V> finished) {
            Work<K, V> next;
            CompletableFuture<Void> drained = null;
            synchronized (this) {
                Slot<K, V> slot = slots.get(finished.key);
                if (slot != finished.slot || slot.active != finished) {
                    return;
                }
                next = slot.pending;
                if (next == null) {
                    slots.remove(finished.key, slot);
                    slot.active = null;
                    drained = slot.drained;
                } else {
                    slot.pending = null;
                    slot.active = next;
                    SUBMITTED.increment();
                }
            }
            if (drained != null) {
                // A barrier records durability/liveness, not the status of one
                // write. Vanilla receives failures through acknowledgement.
                drained.complete(null);
                onIdle.accept(this);
            } else {
                start(next, 1);
            }
        }

        private static final class Slot<K, V> {
            private Work<K, V> active;
            private Work<K, V> pending;
            private final CompletableFuture<Void> drained;

            private Slot(CompletableFuture<Void> drained) {
                this.drained = drained;
            }
        }

        private static final class Work<K, V> {
            private final K key;
            private Supplier<V> value;
            private Function<Supplier<V>, CompletableFuture<Void>> writer;
            private final List<CompletableFuture<Void>> acknowledgements;
            private final Slot<K, V> slot;

            private Work(
                    K key,
                    Supplier<V> value,
                    Function<Supplier<V>, CompletableFuture<Void>> writer,
                    List<CompletableFuture<Void>> acknowledgements,
                    Slot<K, V> slot) {
                this.key = key;
                this.value = value;
                this.writer = writer;
                this.acknowledgements = acknowledgements;
                this.slot = slot;
            }
        }
    }

    private static final class LazyFuture<T> extends CompletableFuture<T> {
        private final Supplier<T> serializer;
        private final Executor executor;
        private final AtomicBoolean started = new AtomicBoolean();

        private LazyFuture(Supplier<T> serializer, Executor executor) {
            this.serializer = Objects.requireNonNull(serializer, "serializer");
            this.executor = Objects.requireNonNull(executor, "executor");
        }

        @Override
        public T join() {
            start();
            return super.join();
        }

        @Override
        public T get() throws InterruptedException, ExecutionException {
            start();
            return super.get();
        }

        @Override
        public T get(long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            start();
            return super.get(timeout, unit);
        }

        private void start() {
            if (isDone() || !started.compareAndSet(false, true)) {
                return;
            }
            try {
                executor.execute(() -> {
                    try {
                        complete(serializer.get());
                    } catch (Throwable error) {
                        completeExceptionally(error);
                    }
                });
            } catch (Throwable error) {
                completeExceptionally(error);
            }
        }
    }
}
