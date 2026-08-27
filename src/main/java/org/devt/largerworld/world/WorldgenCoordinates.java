package org.devt.largerworld.world;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.noise.NoiseConfig;
import net.minecraft.world.gen.chunk.ChunkNoiseSampler;
import net.minecraft.world.gen.chunk.AquiferSampler;
import org.devt.largerworld.coordinate.CellPos;
import org.devt.largerworld.coordinate.VirtualPosition;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Coordinate offsets associated with each cell world's private noise config.
 *
 * <p>Vanilla world-generation APIs expose horizontal coordinates as {@code int}.
 * The virtual world is much larger, so sampling coordinates intentionally use
 * two's-complement modulo 2^32. This keeps neighboring samples consecutive and
 * deterministic instead of rejecting cells whose global block position exceeds
 * {@link Integer#MAX_VALUE}.
 */
public final class WorldgenCoordinates {
    public static final int CELL_SIZE_CHUNKS = (int) (VirtualPosition.CELL_SIZE / 16L);

    private static final Map<NoiseConfig, CellPos> NOISE_CONFIG_CELLS = new IdentityHashMap<>();
    private static final Map<ChunkNoiseSampler, CellPos> SAMPLER_CELLS = new WeakHashMap<>();
    private static final Map<AquiferSampler, CellPos> AQUIFER_CELLS = new WeakHashMap<>();

    private WorldgenCoordinates() {
    }

    public static synchronized void register(NoiseConfig noiseConfig, CellPos cell) {
        NOISE_CONFIG_CELLS.put(noiseConfig, cell);
    }

    public static synchronized CellPos cell(NoiseConfig noiseConfig) {
        return NOISE_CONFIG_CELLS.getOrDefault(noiseConfig, CellPos.ZERO);
    }

    public static synchronized void register(ChunkNoiseSampler sampler, CellPos cell) {
        SAMPLER_CELLS.put(sampler, cell);
    }

    public static synchronized CellPos cell(ChunkNoiseSampler sampler) {
        return SAMPLER_CELLS.getOrDefault(sampler, CellPos.ZERO);
    }

    public static synchronized void register(AquiferSampler sampler, CellPos cell) {
        AQUIFER_CELLS.put(sampler, cell);
    }

    public static synchronized CellPos cell(AquiferSampler sampler) {
        return AQUIFER_CELLS.getOrDefault(sampler, CellPos.ZERO);
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

    public static BlockPos toGlobalBlock(CellPos cell, BlockPos localPos) {
        return new BlockPos(
                toGlobalBlockX(cell, localPos.getX()),
                localPos.getY(),
                toGlobalBlockZ(cell, localPos.getZ()));
    }

    public static BlockPos toLocalBlock(CellPos cell, BlockPos globalPos) {
        return new BlockPos(
                subtractWrapped(globalPos.getX(), cell.x(), VirtualPosition.CELL_SIZE),
                globalPos.getY(),
                subtractWrapped(globalPos.getZ(), cell.z(), VirtualPosition.CELL_SIZE));
    }

    private static int addWrapped(int local, long cellCoordinate, long cellSize) {
        return (int) (local + cellCoordinate * cellSize);
    }

    private static int subtractWrapped(int global, long cellCoordinate, long cellSize) {
        return (int) (global - cellCoordinate * cellSize);
    }

}
