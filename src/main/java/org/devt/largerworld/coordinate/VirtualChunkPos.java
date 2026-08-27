package org.devt.largerworld.coordinate;

/**
 * Chunk identity used by the multiplayer/network layer. Local chunk coordinates
 * are canonical inside one cell; client coordinates are relative to a player's
 * current cell and therefore remain ordinary ints.
 */
public record VirtualChunkPos(CellPos cell, int localX, int localZ) {
    public static final int CELL_CHUNKS = (int) (VirtualPosition.CELL_SIZE / 16);
    public static final int HALF_CELL_CHUNKS = CELL_CHUNKS / 2;

    public VirtualChunkPos {
        if (!isCanonical(localX, localZ)) {
            throw new IllegalArgumentException("Local chunk coordinates must be canonical");
        }
    }

    public static boolean isCanonical(int localX, int localZ) {
        return localX >= -HALF_CELL_CHUNKS && localX < HALF_CELL_CHUNKS
                && localZ >= -HALF_CELL_CHUNKS && localZ < HALF_CELL_CHUNKS;
    }

    public static VirtualChunkPos fromClient(CellPos playerCell, int clientX, int clientZ) {
        long deltaX = Math.floorDiv((long) clientX + HALF_CELL_CHUNKS, CELL_CHUNKS);
        long deltaZ = Math.floorDiv((long) clientZ + HALF_CELL_CHUNKS, CELL_CHUNKS);
        int localX = (int) (clientX - deltaX * CELL_CHUNKS);
        int localZ = (int) (clientZ - deltaZ * CELL_CHUNKS);
        return new VirtualChunkPos(playerCell.add(deltaX, deltaZ), localX, localZ);
    }

    public int clientX(CellPos playerCell) {
        return toClientCoordinate(cell.x(), playerCell.x(), localX);
    }

    public int clientZ(CellPos playerCell) {
        return toClientCoordinate(cell.z(), playerCell.z(), localZ);
    }

    /** Maps packet/control coordinates too; {@code local} may transiently cross a seam. */
    public static int toClientCoordinate(long sourceCell, long originCell, int local) {
        long cellDelta = Math.subtractExact(sourceCell, originCell);
        long result = Math.addExact(Math.multiplyExact(cellDelta, CELL_CHUNKS), local);
        return Math.toIntExact(result);
    }
}
