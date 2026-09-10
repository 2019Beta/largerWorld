package org.devt.largerworld.world;

import java.math.BigInteger;

/**
 * Samples a bounded native noise field in smoothly blended global patches.
 * Patch identities use all coordinate bits; only within-patch distances become
 * doubles. Adjacent cells and signed-int wrap points therefore use one field.
 */
public final class ContinuousNoiseCoordinates {
    private static final int PATCH_BITS = 16;
    private static final int PATCH_SIZE = 1 << PATCH_BITS;
    private static final BigInteger MASK = BigInteger.valueOf(PATCH_SIZE - 1);
    private static final BigInteger START = BigInteger.ONE.shiftLeft(30);
    // Finish transitioning before the first signed block-int discontinuity.
    private static final BigInteger END = BigInteger.ONE.shiftLeft(31)
            .subtract(BigInteger.valueOf(1 << 20));

    private ContinuousNoiseCoordinates() {
    }

    public static double activation(BigInteger x, BigInteger z) {
        BigInteger distance = x.abs().max(z.abs());
        if (distance.compareTo(START) <= 0) {
            return 0;
        }
        if (distance.compareTo(END) >= 0) {
            return 1;
        }
        return fade(distance.subtract(START).doubleValue() / END.subtract(START).doubleValue());
    }

    public static double sample(BigInteger x, BigInteger z, long seed, Sampler sampler) {
        BigInteger patchX = x.shiftRight(PATCH_BITS);
        BigInteger patchZ = z.shiftRight(PATCH_BITS);
        int localX = x.and(MASK).intValue();
        int localZ = z.and(MASK).intValue();
        double tx = fade(localX / (double) PATCH_SIZE);
        double tz = fade(localZ / (double) PATCH_SIZE);
        double v00 = corner(patchX, patchZ, localX, localZ, seed, sampler);
        if (localX == 0 && localZ == 0) {
            return v00;
        }
        if (localX == 0) {
            return lerp(v00, corner(patchX, patchZ.add(BigInteger.ONE),
                    localX, localZ - PATCH_SIZE, seed, sampler), tz);
        }
        if (localZ == 0) {
            return lerp(v00, corner(patchX.add(BigInteger.ONE), patchZ,
                    localX - PATCH_SIZE, localZ, seed, sampler), tx);
        }
        double v10 = corner(patchX.add(BigInteger.ONE), patchZ,
                localX - PATCH_SIZE, localZ, seed, sampler);
        double v01 = corner(patchX, patchZ.add(BigInteger.ONE),
                localX, localZ - PATCH_SIZE, seed, sampler);
        double v11 = corner(patchX.add(BigInteger.ONE), patchZ.add(BigInteger.ONE),
                localX - PATCH_SIZE, localZ - PATCH_SIZE, seed, sampler);
        return lerp(lerp(v00, v10, tx), lerp(v01, v11, tx), tz);
    }

    private static double corner(BigInteger x, BigInteger z, int localX, int localZ,
                                 long seed, Sampler sampler) {
        long hash = mix(seed ^ 0x43504e5f56310001L, x.toByteArray());
        hash = mix(hash ^ 0x9e3779b97f4a7c15L, z.toByteArray());
        int offsetX = (int) (hash & 0xfffff) - 0x80000;
        int offsetZ = (int) ((hash >>> 32) & 0xfffff) - 0x80000;
        return sampler.sample(offsetX + localX, offsetZ + localZ);
    }

    private static long mix(long seed, byte[] bytes) {
        long hash = seed ^ bytes.length;
        for (byte value : bytes) {
            hash ^= value & 255L;
            hash *= 0x100000001b3L;
            hash ^= hash >>> 29;
        }
        hash ^= hash >>> 30;
        hash *= 0xbf58476d1ce4e5b9L;
        hash ^= hash >>> 27;
        hash *= 0x94d049bb133111ebL;
        return hash ^ (hash >>> 31);
    }

    private static double fade(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static double lerp(double start, double end, double t) {
        return start + (end - start) * t;
    }

    @FunctionalInterface
    public interface Sampler {
        double sample(double boundedX, double boundedZ);
    }
}
