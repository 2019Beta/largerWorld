package org.devt.largerworld.coordinate;

import java.math.BigInteger;

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
        return toClientCoordinate(cell, playerCell, localX, true);
    }

    public int clientZ(CellPos playerCell) {
        return toClientCoordinate(cell, playerCell, localZ, false);
    }

    /** Maps packet/control coordinates too; {@code local} may transiently cross a seam. */
    public static int toClientCoordinate(long sourceCell, long originCell, int local) {
        return toClientCoordinate(
                BigInteger.valueOf(sourceCell), BigInteger.valueOf(originCell), local);
    }

    /** Arbitrary-precision form used by packet translations at distant cells. */
    public static int toClientCoordinate(
            BigInteger sourceCell, BigInteger originCell, int local) {
        return sourceCell.subtract(originCell)
                .multiply(BigInteger.valueOf(CELL_CHUNKS))
                .add(BigInteger.valueOf(local))
                .intValueExact();
    }

    private static int toClientCoordinate(
            CellPos sourceCell, CellPos originCell, int local, boolean xAxis) {
        BigInteger delta = xAxis
                ? sourceCell.deltaX(originCell)
                : sourceCell.deltaZ(originCell);
        return delta.multiply(BigInteger.valueOf(CELL_CHUNKS))
                .add(BigInteger.valueOf(local))
                .intValueExact();
    }
}
