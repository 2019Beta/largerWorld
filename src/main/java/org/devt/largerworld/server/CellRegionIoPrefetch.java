package org.devt.largerworld.server;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerChunkLoadingManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Short-lived cache of asynchronous RegionFile reads started by prediction.
 * The normal chunk loader consumes the same future through a narrow mixin hook,
 * avoiding a second disk read without parsing or mutating chunks off-thread.
 */
public final class CellRegionIoPrefetch {
    private static final long ENTRY_TTL_NANOS = TimeUnit.SECONDS.toNanos(
            Long.getLong("largerworld.regionPrefetchTtlSeconds", 15L));
    private static final Map<ServerChunkLoadingManager, Map<Long, Entry>> READS =
            new ConcurrentHashMap<>();
    private static final LongAdder STARTED = new LongAdder();
    private static final LongAdder CONSUMED = new LongAdder();
    private static final LongAdder EXPIRED = new LongAdder();

    private CellRegionIoPrefetch() {
    }

    public static CompletableFuture<Optional<NbtCompound>> prefetch(
            ServerWorld world, ChunkPos pos) {
        ServerChunkLoadingManager manager = world.getChunkManager().chunkLoadingManager;
        long key = pos.toLong();
        long now = System.nanoTime();
        AtomicReference<Entry> selected = new AtomicReference<>();
        READS.compute(manager, (ignored, existingReads) -> {
            Map<Long, Entry> reads = existingReads == null
                    ? new ConcurrentHashMap<>() : existingReads;
            Entry cached = reads.get(key);
            if (cached == null || cached.isExpired(now)) {
                if (cached != null) {
                    EXPIRED.increment();
                }
                STARTED.increment();
                cached = new Entry(now, manager.getNbt(pos));
                reads.put(key, cached);
            }
            selected.set(cached);
            return reads;
        });
        return selected.get().future();
    }

    /** Called only from the real loader's getUpdatedChunkNbt path. */
    public static CompletableFuture<Optional<NbtCompound>> consumeOrRead(
            ServerChunkLoadingManager manager,
            ChunkPos pos,
            Supplier<CompletableFuture<Optional<NbtCompound>>> fallback) {
        AtomicReference<Entry> selected = new AtomicReference<>();
        READS.computeIfPresent(manager, (ignored, reads) -> {
            selected.set(reads.remove(pos.toLong()));
            return reads.isEmpty() ? null : reads;
        });
        Entry entry = selected.get();
        if (entry == null) {
            return fallback.get();
        }
        if (entry.isExpired(System.nanoTime())) {
            EXPIRED.increment();
            return fallback.get();
        }
        CONSUMED.increment();
        return entry.future();
    }

    public static void evictExpired() {
        long now = System.nanoTime();
        for (ServerChunkLoadingManager manager : READS.keySet()) {
            READS.computeIfPresent(manager, (ignored, reads) -> {
                reads.entrySet().removeIf(entry -> {
                    boolean expired = entry.getValue().isExpired(now);
                    if (expired) {
                        EXPIRED.increment();
                    }
                    return expired;
                });
                return reads.isEmpty() ? null : reads;
            });
        }
    }

    public static Statistics statistics() {
        int cached = READS.values().stream().mapToInt(Map::size).sum();
        return new Statistics(cached, STARTED.sum(), CONSUMED.sum(), EXPIRED.sum());
    }

    public static void clearServerState() {
        READS.clear();
        STARTED.reset();
        CONSUMED.reset();
        EXPIRED.reset();
    }

    public record Statistics(int cached, long started, long consumed, long expired) {
    }

    private record Entry(
            long startedNanos,
            CompletableFuture<Optional<NbtCompound>> future) {
        private boolean isExpired(long now) {
            return now - startedNanos >= ENTRY_TTL_NANOS;
        }
    }
}
