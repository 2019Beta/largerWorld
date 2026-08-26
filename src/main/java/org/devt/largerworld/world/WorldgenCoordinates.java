package org.devt.largerworld.world;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import org.devt.largerworld.coordinate.CellPos;
import org.devt.largerworld.coordinate.VirtualPosition;

import java.util.IdentityHashMap;
import java.util.Map;

/** Temporary coordinate view used while vanilla generates a cell chunk. */
public final class WorldgenCoordinates {
    public static final int CELL_SIZE_CHUNKS = (int) (VirtualPosition.CELL_SIZE / 16L);

    private static final Map<Chunk, ActiveShift> ACTIVE_CHUNKS = new IdentityHashMap<>();

    private WorldgenCoordinates() {
    }

    public static synchronized void begin(Chunk chunk, CellPos cell) {
        ActiveShift active = ACTIVE_CHUNKS.get(chunk);
        if (active == null) {
            ACTIVE_CHUNKS.put(chunk, new ActiveShift(cell, 1));
            return;
        }
        if (!active.cell().equals(cell)) {
            throw new IllegalStateException("Chunk is already being generated for another coordinate cell");
        }
        ACTIVE_CHUNKS.put(chunk, new ActiveShift(cell, active.references() + 1));
    }

    public static synchronized void end(Chunk chunk) {
        ActiveShift active = ACTIVE_CHUNKS.get(chunk);
        if (active == null) {
            throw new IllegalStateException("World-generation coordinate shift is not active");
        }
        if (active.references() == 1) {
            ACTIVE_CHUNKS.remove(chunk);
        } else {
            ACTIVE_CHUNKS.put(chunk, new ActiveShift(active.cell(), active.references() - 1));
        }
    }

    public static synchronized boolean isShifted(Chunk chunk) {
        return ACTIVE_CHUNKS.containsKey(chunk);
    }

    public static synchronized ChunkPos shiftedPos(Chunk chunk, ChunkPos localPos) {
        ActiveShift active = ACTIVE_CHUNKS.get(chunk);
        return active == null ? localPos : toGlobalChunk(active.cell(), localPos);
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

    private record ActiveShift(CellPos cell, int references) {
    }
}
