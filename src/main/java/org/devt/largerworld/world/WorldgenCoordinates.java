package org.devt.largerworld.world;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.noise.NoiseConfig;
import net.minecraft.world.gen.chunk.ChunkNoiseSampler;
import net.minecraft.world.gen.chunk.AquiferSampler;
import org.devt.largerworld.coordinate.CellPos;
import org.devt.largerworld.coordinate.VirtualPosition;

import java.math.BigInteger;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Coordinate offsets associated with each cell world's private noise config.
 *
 * <p>Vanilla world-generation APIs expose horizontal coordinates as {@code int}.
 * Calls that must remain spatially continuous use a two's-complement folded
 * coordinate, while random-only calls hash the complete {@link BigInteger}
 * coordinate with a domain separator. The independent density overlay is
 * evaluated directly in the arbitrary-precision coordinate plane, so the
 * generated terrain has no fixed 32-bit coordinate period.
 */
public final class WorldgenCoordinates {
    public static final int CELL_SIZE_CHUNKS = (int) (VirtualPosition.CELL_SIZE / 16L);

    private static final Map<NoiseConfig, Context> NOISE_CONFIG_CONTEXTS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<ChunkNoiseSampler, Context> SAMPLER_CONTEXTS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<AquiferSampler, Context> AQUIFER_CONTEXTS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<ChunkNoiseSampler, Map<Long, Double>> DENSITY_CACHES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private WorldgenCoordinates() {
    }

    public static void register(NoiseConfig noiseConfig, CellPos cell, long seed) {
        NOISE_CONFIG_CONTEXTS.put(noiseConfig, new Context(cell, seed));
    }

    public static void unregister(NoiseConfig noiseConfig) {
        NOISE_CONFIG_CONTEXTS.remove(noiseConfig);
    }

    public static void clearServerState() {
        NOISE_CONFIG_CONTEXTS.clear();
        SAMPLER_CONTEXTS.clear();
        AQUIFER_CONTEXTS.clear();
        DENSITY_CACHES.clear();
    }

    public static CellPos cell(NoiseConfig noiseConfig) {
        return context(noiseConfig).cell();
    }

    public static long seed(NoiseConfig noiseConfig) {
        return context(noiseConfig).seed();
    }

    public static void register(ChunkNoiseSampler sampler, NoiseConfig noiseConfig) {
        SAMPLER_CONTEXTS.put(sampler, context(noiseConfig));
        DENSITY_CACHES.put(sampler, new ConcurrentHashMap<>());
    }

    public static CellPos cell(ChunkNoiseSampler sampler) {
        return context(sampler).cell();
    }

    public static long seed(ChunkNoiseSampler sampler) {
        return context(sampler).seed();
    }

    public static double densityOffset(ChunkNoiseSampler sampler, int foldedX, int foldedZ) {
        Context context = context(sampler);
        int localX = toLocalBlockX(context.cell(), foldedX);
        int localZ = toLocalBlockZ(context.cell(), foldedZ);
        long key = ((long) localX << 32) ^ (localZ & 0xffffffffL);
        Map<Long, Double> cache = DENSITY_CACHES.get(sampler);
        if (cache == null) {
            return ArbitraryPrecisionWorldgen.densityOffset(
                    context.cell(), localX, localZ, context.seed());
        }
        return cache.computeIfAbsent(key, ignored ->
                ArbitraryPrecisionWorldgen.densityOffset(
                        context.cell(), localX, localZ, context.seed()));
    }

    public static void register(AquiferSampler sampler, NoiseConfig noiseConfig) {
        AQUIFER_CONTEXTS.put(sampler, context(noiseConfig));
    }

    public static CellPos cell(AquiferSampler sampler) {
        return context(sampler).cell();
    }

    public static CellPos cell(ChunkGenerator generator) {
        if (generator.getBiomeSource() instanceof CellBiomeSource source) {
            return source.cell();
        }
        return CellPos.ZERO;
    }

    public static ChunkPos toGlobalChunk(CellPos cell, ChunkPos localPos) {
        return new ChunkPos(
                addWrapped(localPos.x, cell.x(), CELL_SIZE_CHUNKS),
                addWrapped(localPos.z, cell.z(), CELL_SIZE_CHUNKS));
    }

    public static int toLocalChunkX(CellPos cell, int globalChunkX) {
        return subtractWrapped(globalChunkX, cell.x(), CELL_SIZE_CHUNKS);
    }

    public static int toLocalChunkZ(CellPos cell, int globalChunkZ) {
        return subtractWrapped(globalChunkZ, cell.z(), CELL_SIZE_CHUNKS);
    }

    public static int toGlobalBlockX(CellPos cell, int localBlockX) {
        return addWrapped(localBlockX, cell.x(), VirtualPosition.CELL_SIZE);
    }

    public static int toGlobalBlockZ(CellPos cell, int localBlockZ) {
        return addWrapped(localBlockZ, cell.z(), VirtualPosition.CELL_SIZE);
    }

    public static int toLocalBlockX(CellPos cell, int globalBlockX) {
        return subtractWrapped(globalBlockX, cell.x(), VirtualPosition.CELL_SIZE);
    }

    public static int toLocalBlockZ(CellPos cell, int globalBlockZ) {
        return subtractWrapped(globalBlockZ, cell.z(), VirtualPosition.CELL_SIZE);
    }

    public static BlockPos toGlobalBlock(CellPos cell, BlockPos localPos) {
        return new BlockPos(
                toGlobalBlockX(cell, localPos.getX()),
                localPos.getY(),
                toGlobalBlockZ(cell, localPos.getZ()));
    }

    public static BlockPos toLocalBlock(CellPos cell, BlockPos globalPos) {
        return new BlockPos(
                toLocalBlockX(cell, globalPos.getX()),
                globalPos.getY(),
                toLocalBlockZ(cell, globalPos.getZ()));
    }

    /** A non-periodic pair of int tokens for vanilla random APIs that only accept chunk ints. */
    public static ChunkPos toRandomChunk(CellPos cell, ChunkPos localPos, long domain) {
        BigInteger globalX = globalChunk(cell.x(), localPos.x);
        BigInteger globalZ = globalChunk(cell.z(), localPos.z);
        return new ChunkPos(
                hashToInt(globalX, globalZ, domain),
                hashToInt(globalX, globalZ, domain ^ 0x9e3779b97f4a7c15L));
    }

    public static BlockPos toRandomBlock(CellPos cell, int localX, int y, int localZ, long domain) {
        BigInteger globalX = globalBlock(cell.x(), localX);
        BigInteger globalZ = globalBlock(cell.z(), localZ);
        return new BlockPos(
                hashToInt(globalX, globalZ, domain),
                y,
                hashToInt(globalX, globalZ, domain ^ 0x9e3779b97f4a7c15L));
    }

    public static BigInteger globalBlockX(CellPos cell, int localX) {
        return globalBlock(cell.x(), localX);
    }

    public static BigInteger globalBlockZ(CellPos cell, int localZ) {
        return globalBlock(cell.z(), localZ);
    }

    private static Context context(NoiseConfig config) {
        return NOISE_CONFIG_CONTEXTS.getOrDefault(config, Context.ZERO);
    }

    private static Context context(ChunkNoiseSampler sampler) {
        return SAMPLER_CONTEXTS.getOrDefault(sampler, Context.ZERO);
    }

    private static Context context(AquiferSampler sampler) {
        return AQUIFER_CONTEXTS.getOrDefault(sampler, Context.ZERO);
    }

    private static BigInteger globalBlock(BigInteger cellCoordinate, int local) {
        return cellCoordinate.shiftLeft(20).add(BigInteger.valueOf(local));
    }

    private static BigInteger globalChunk(BigInteger cellCoordinate, int local) {
        return cellCoordinate.shiftLeft(16).add(BigInteger.valueOf(local));
    }

    private static int addWrapped(int local, BigInteger cellCoordinate, long cellSize) {
        return cellCoordinate.multiply(BigInteger.valueOf(cellSize))
                .add(BigInteger.valueOf(local))
                .intValue();
    }

    private static int subtractWrapped(int global, BigInteger cellCoordinate, long cellSize) {
        return BigInteger.valueOf(global)
                .subtract(cellCoordinate.multiply(BigInteger.valueOf(cellSize)))
                .intValue();
    }

    private static int hashToInt(BigInteger x, BigInteger z, long domain) {
        long hash = mixBytes(0x6a09e667f3bcc909L ^ domain, x.toByteArray());
        hash = mixBytes(hash ^ 0xbb67ae8584caa73bL, z.toByteArray());
        hash ^= hash >>> 30;
        hash *= 0xbf58476d1ce4e5b9L;
        hash ^= hash >>> 27;
        hash *= 0x94d049bb133111ebL;
        return (int) (hash ^ hash >>> 31);
    }

    private static long mixBytes(long state, byte[] bytes) {
        long value = state ^ bytes.length;
        for (byte current : bytes) {
            value ^= current & 0xffL;
            value *= 0x100000001b3L;
            value ^= value >>> 29;
        }
        return value;
    }

    private record Context(CellPos cell, long seed) {
        private static final Context ZERO = new Context(CellPos.ZERO, 0L);
    }
}
