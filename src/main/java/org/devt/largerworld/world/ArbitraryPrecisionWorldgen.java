package org.devt.largerworld.world;

import org.devt.largerworld.coordinate.CellPos;

import java.math.BigInteger;

/**
 * A continuous, non-periodic density overlay evaluated directly in the
 * arbitrary-precision global coordinate plane.
 */
public final class ArbitraryPrecisionWorldgen {
    private static final BigInteger ACTIVATION_START = BigInteger.ONE.shiftLeft(30);
    private static final BigInteger ACTIVATION_END = BigInteger.ONE.shiftLeft(31);

    private ArbitraryPrecisionWorldgen() {
    }

    public static double densityOffset(
            CellPos cell, int localX, int localZ, long worldSeed) {
        BigInteger globalX = WorldgenCoordinates.globalBlockX(cell, localX);
        BigInteger globalZ = WorldgenCoordinates.globalBlockZ(cell, localZ);
        double activation = activation(globalX.abs().max(globalZ.abs()));
        if (activation == 0.0) {
            return 0.0;
        }

        double broad = valueNoise(globalX, globalZ, 2048, worldSeed ^ 0x243f6a8885a308d3L);
        double detail = valueNoise(globalX, globalZ, 256, worldSeed ^ 0x13198a2e03707344L);
        return activation * (broad * 0.035 + detail * 0.0125);
    }

    private static double activation(BigInteger distance) {
        if (distance.compareTo(ACTIVATION_START) <= 0) {
            return 0.0;
        }
        if (distance.compareTo(ACTIVATION_END) >= 0) {
            return 1.0;
        }
        double t = distance.subtract(ACTIVATION_START).doubleValue()
                / ACTIVATION_END.subtract(ACTIVATION_START).doubleValue();
        return smooth(t);
    }

    private static double valueNoise(
            BigInteger x, BigInteger z, int scale, long seed) {
        Divided dx = floorDivide(x, scale);
        Divided dz = floorDivide(z, scale);
        double tx = smooth(dx.remainder() / (double) scale);
        double tz = smooth(dz.remainder() / (double) scale);

        double v00 = lattice(dx.quotient(), dz.quotient(), seed);
        double v10 = lattice(dx.quotient().add(BigInteger.ONE), dz.quotient(), seed);
        double v01 = lattice(dx.quotient(), dz.quotient().add(BigInteger.ONE), seed);
        double v11 = lattice(
                dx.quotient().add(BigInteger.ONE),
                dz.quotient().add(BigInteger.ONE), seed);
        return lerp(lerp(v00, v10, tx), lerp(v01, v11, tx), tz);
    }

    private static Divided floorDivide(BigInteger value, int divisor) {
        BigInteger bigDivisor = BigInteger.valueOf(divisor);
        BigInteger[] divided = value.divideAndRemainder(bigDivisor);
        if (divided[1].signum() < 0) {
            divided[0] = divided[0].subtract(BigInteger.ONE);
            divided[1] = divided[1].add(bigDivisor);
        }
        return new Divided(divided[0], divided[1].intValue());
    }

    private static double lattice(BigInteger x, BigInteger z, long seed) {
        long hash = mixBytes(seed ^ 0x9e3779b97f4a7c15L, x.toByteArray());
        hash = mixBytes(hash ^ 0xd1b54a32d192ed03L, z.toByteArray());
        hash ^= hash >>> 30;
        hash *= 0xbf58476d1ce4e5b9L;
        hash ^= hash >>> 27;
        hash *= 0x94d049bb133111ebL;
        hash ^= hash >>> 31;
        return ((hash >>> 11) * 0x1.0p-53) * 2.0 - 1.0;
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

    private static double smooth(double value) {
        return value * value * (3.0 - 2.0 * value);
    }

    private static double lerp(double start, double end, double delta) {
        return start + (end - start) * delta;
    }

    private record Divided(BigInteger quotient, int remainder) {
    }
}
