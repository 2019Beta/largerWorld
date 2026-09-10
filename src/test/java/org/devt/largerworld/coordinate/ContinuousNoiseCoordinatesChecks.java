package org.devt.largerworld.coordinate;

import org.devt.largerworld.world.ContinuousNoiseCoordinates;
import org.devt.largerworld.world.WorldgenCoordinates;
import java.math.BigInteger;

/** Regression definitions for negative patches, wrap seams, and high-bit identity. */
public final class ContinuousNoiseCoordinatesChecks {
    private ContinuousNoiseCoordinatesChecks() {
    }

    public static void run() {
        BigInteger huge = BigInteger.ONE.shiftLeft(200);
        for (BigInteger seam : new BigInteger[] {
                BigInteger.ONE.shiftLeft(31), BigInteger.ONE.shiftLeft(31).negate(),
                huge, huge.negate(), huge.add(BigInteger.valueOf(524288))}) {
            double before = sample(seam.subtract(BigInteger.ONE), BigInteger.valueOf(17));
            double after = sample(seam, BigInteger.valueOf(17));
            require(Math.abs(after - before) < 0.001, "noise continuity at " + seam);
            require(ContinuousNoiseCoordinates.activation(seam, BigInteger.ZERO) == 1,
                    "native folded field must be fully replaced before wrap");
        }
        require(Double.compare(sample(huge, huge), sample(huge.add(BigInteger.ONE.shiftLeft(32)), huge)) != 0,
                "high bits distinguish the old block period");
        CellPos west = new CellPos(huge, huge.negate());
        CellPos east = west.add(1, 0);
        BigInteger fromWest = WorldgenCoordinates.globalBlockX(west, 524288);
        BigInteger fromEast = WorldgenCoordinates.globalBlockX(east, -524288);
        require(fromWest.equals(fromEast), "both cell representations use the same point");
        require(Double.compare(sample(fromWest, huge), sample(fromEast, huge)) == 0,
                "same boundary point has exactly the same noise");
        for (long cell : new long[] {2047, 2048, -2048, -2049, 4096}) {
            CellPos pos = new CellPos(cell, -cell);
            for (int biome : new int[] {-131072, -1, 0, 131071}) {
                require(WorldgenCoordinates.toGlobalBiomeX(pos, biome) << 2
                                == WorldgenCoordinates.toGlobalBlockX(pos, biome << 2),
                        "biome/density block folding agrees");
            }
        }
    }

    private static double sample(BigInteger x, BigInteger z) {
        return ContinuousNoiseCoordinates.sample(x, z, 42, (bx, bz) -> {
            require(Math.abs(bx) <= 589824 && Math.abs(bz) <= 589824, "bounded native sample");
            return Math.sin(bx / 4096.0) * Math.cos(bz / 4096.0);
        });
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
