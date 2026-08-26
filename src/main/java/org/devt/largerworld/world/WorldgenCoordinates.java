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

/** Coordinate offsets associated with each cell world's private noise config. */
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
                addExact(localPos.x, chunkOffset(cell.x())),
                addExact(localPos.z, chunkOffset(cell.z())));
    }

    public static int toLocalChunkX(CellPos cell, int globalChunkX) {
        return subtractExact(globalChunkX, chunkOffset(cell.x()));
    }

    public static int toLocalChunkZ(CellPos cell, int globalChunkZ) {
        return subtractExact(globalChunkZ, chunkOffset(cell.z()));
    }

    public static int toGlobalBlockX(CellPos cell, int localBlockX) {
        return addExact(localBlockX, blockOffset(cell.x()));
    }

    public static int toGlobalBlockZ(CellPos cell, int localBlockZ) {
        return addExact(localBlockZ, blockOffset(cell.z()));
    }

    public static BlockPos toGlobalBlock(CellPos cell, BlockPos localPos) {
        return new BlockPos(
                toGlobalBlockX(cell, localPos.getX()),
                localPos.getY(),
                toGlobalBlockZ(cell, localPos.getZ()));
    }

    public static BlockPos toLocalBlock(CellPos cell, BlockPos globalPos) {
        return new BlockPos(
                subtractExact(globalPos.getX(), blockOffset(cell.x())),
                globalPos.getY(),
                subtractExact(globalPos.getZ(), blockOffset(cell.z())));
    }

    private static long chunkOffset(long cellCoordinate) {
        return Math.multiplyExact(cellCoordinate, (long) CELL_SIZE_CHUNKS);
    }

    private static long blockOffset(long cellCoordinate) {
        return Math.multiplyExact(cellCoordinate, VirtualPosition.CELL_SIZE);
    }

    private static int addExact(int value, long offset) {
        return Math.toIntExact(Math.addExact((long) value, offset));
    }

    private static int subtractExact(int value, long offset) {
        return Math.toIntExact(Math.subtractExact((long) value, offset));
    }

}
