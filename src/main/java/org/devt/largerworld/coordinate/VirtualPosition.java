package org.devt.largerworld.coordinate;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * Exact cell coordinates paired with engine-safe local coordinates.
 *
 * <p>The canonical local interval is [-524288, 524288). Global X/Z are
 * calculated with BigDecimal, so displaying a far-away position does not first
 * lose the cell component through a double conversion.</p>
 */
public record VirtualPosition(CellPos cell, double localX, double y, double localZ) {
    public static final long CELL_SIZE = 1L << 20;
    public static final long HALF_CELL = CELL_SIZE / 2;

    private static final BigDecimal CELL_SIZE_DECIMAL = BigDecimal.valueOf(CELL_SIZE);
    private static final BigDecimal HALF_CELL_DECIMAL = BigDecimal.valueOf(HALF_CELL);

    public VirtualPosition {
        if (cell == null) {
            throw new NullPointerException("cell");
        }
        if (!Double.isFinite(localX) || !Double.isFinite(y) || !Double.isFinite(localZ)) {
            throw new IllegalArgumentException("Local coordinates must be finite");
        }
        if (localX < -HALF_CELL || localX >= HALF_CELL
                || localZ < -HALF_CELL || localZ >= HALF_CELL) {
            throw new IllegalArgumentException("Local X/Z must be canonical");
        }
    }

    public static VirtualPosition normalize(CellPos cell, double localX, double y, double localZ) {
        requireFinite(localX, y, localZ);
        long deltaX = cellDelta(localX);
        long deltaZ = cellDelta(localZ);
        CellPos normalizedCell = cell.add(deltaX, deltaZ);
        double normalizedX = normalizeLocal(localX, deltaX);
        double normalizedZ = normalizeLocal(localZ, deltaZ);
        return new VirtualPosition(normalizedCell, normalizedX, y, normalizedZ);
    }

    public static VirtualPosition fromGlobal(BigDecimal globalX, double y, BigDecimal globalZ) {
        if (globalX == null || globalZ == null || !Double.isFinite(y)) {
            throw new IllegalArgumentException("Global coordinates and Y must be finite");
        }

        long cellX = globalCell(globalX);
        long cellZ = globalCell(globalZ);
        double localX = globalX.subtract(CELL_SIZE_DECIMAL.multiply(BigDecimal.valueOf(cellX))).doubleValue();
        double localZ = globalZ.subtract(CELL_SIZE_DECIMAL.multiply(BigDecimal.valueOf(cellZ))).doubleValue();
        return normalize(new CellPos(cellX, cellZ), localX, y, localZ);
    }

    /** Converts a connection-space X coordinate into the local X of a source cell. */
    public static double clientToLocalX(CellPos sourceCell, CellPos originCell, double clientX) {
        return clientX - ((double) sourceCell.x() - (double) originCell.x()) * CELL_SIZE;
    }

    /** Converts a connection-space Z coordinate into the local Z of a source cell. */
    public static double clientToLocalZ(CellPos sourceCell, CellPos originCell, double clientZ) {
        return clientZ - ((double) sourceCell.z() - (double) originCell.z()) * CELL_SIZE;
    }

    public BigDecimal globalX() {
        return global(cell.x(), localX);
    }

    public BigDecimal globalZ() {
        return global(cell.z(), localZ);
    }

    public String globalX(int decimals) {
        return format(globalX(), decimals);
    }

    public String globalZ(int decimals) {
        return format(globalZ(), decimals);
    }

    public boolean isInCell(CellPos expected) {
        return cell.equals(expected);
    }

    private static long globalCell(BigDecimal global) {
        BigInteger value = global.add(HALF_CELL_DECIMAL)
                .divide(CELL_SIZE_DECIMAL, 0, RoundingMode.FLOOR)
                .toBigIntegerExact();
        return value.longValueExact();
    }

    private static BigDecimal global(long cell, double local) {
        return CELL_SIZE_DECIMAL.multiply(BigDecimal.valueOf(cell)).add(BigDecimal.valueOf(local));
    }

    private static String format(BigDecimal value, int decimals) {
        return value.setScale(decimals, RoundingMode.HALF_UP).toPlainString();
    }

    private static long cellDelta(double local) {
        double shifted = (local + HALF_CELL) / CELL_SIZE;
        if (shifted < Long.MIN_VALUE || shifted >= Long.MAX_VALUE) {
            throw new ArithmeticException("Local coordinate is too large to normalize");
        }
        return (long) Math.floor(shifted);
    }

    private static double normalizeLocal(double local, long delta) {
        double normalized = local - delta * (double) CELL_SIZE;
        // Absorb a possible one-ulp error at either canonical boundary.
        if (normalized >= HALF_CELL) {
            normalized -= CELL_SIZE;
        } else if (normalized < -HALF_CELL) {
            normalized += CELL_SIZE;
        }
        return normalized;
    }

    private static void requireFinite(double x, double y, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("Coordinates must be finite");
        }
    }
}
