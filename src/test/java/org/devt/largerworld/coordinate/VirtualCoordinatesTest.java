package org.devt.largerworld.coordinate;

import java.math.BigDecimal;

/** Dependency-free checks run by Gradle's coordinateTest task. */
public final class VirtualCoordinatesTest {
    private VirtualCoordinatesTest() {
    }

    public static void main(String[] args) {
        remainsInsideCanonicalCell();
        crossesPositiveBoundary();
        crossesNegativeBoundary();
        normalizesSeveralCellsAtOnce();
        decomposesLargeGlobalCoordinatesExactly();
        rejectsCellOverflow();
    }

    private static void remainsInsideCanonicalCell() {
        VirtualPosition p = VirtualPosition.normalize(CellPos.ZERO, -524288.0, 64, 524287.999);
        equal(0, p.cell().x(), "lower boundary cell");
        equal(-524288.0, p.localX(), "lower boundary local");
        equal(524287.999, p.localZ(), "upper interior local");
    }

    private static void crossesPositiveBoundary() {
        VirtualPosition p = VirtualPosition.normalize(new CellPos(7, 2), 524288.0, 70, 0);
        equal(8, p.cell().x(), "positive cell crossing");
        equal(-524288.0, p.localX(), "positive local wrap");
        equal("8388608.000", p.globalX(3), "positive global continuity");
    }

    private static void crossesNegativeBoundary() {
        VirtualPosition p = VirtualPosition.normalize(CellPos.ZERO, -524288.25, 70, 0);
        equal(-1, p.cell().x(), "negative cell crossing");
        equal(524287.75, p.localX(), "negative local wrap");
        equal("-524288.250", p.globalX(3), "negative global continuity");
    }

    private static void normalizesSeveralCellsAtOnce() {
        VirtualPosition p = VirtualPosition.normalize(new CellPos(3, -4), 2.5 * (1L << 20), 1, -3.5 * (1L << 20));
        equal(6, p.cell().x(), "multi-cell X");
        equal(-7, p.cell().z(), "multi-cell Z");
        equal(-524288.0, p.localX(), "multi-cell local X");
        equal(-524288.0, p.localZ(), "multi-cell local Z");
    }

    private static void decomposesLargeGlobalCoordinatesExactly() {
        VirtualPosition p = VirtualPosition.fromGlobal(
                new BigDecimal("8000000000000.125"), 80, new BigDecimal("-1000000000000.875"));
        equal("8000000000000.125", p.globalX(3), "exact large global X");
        equal("-1000000000000.875", p.globalZ(3), "exact large global Z");
        check(p.localX() >= -524288 && p.localX() < 524288, "canonical large X");
        check(p.localZ() >= -524288 && p.localZ() < 524288, "canonical large Z");
    }

    private static void rejectsCellOverflow() {
        boolean thrown = false;
        try {
            VirtualPosition.normalize(new CellPos(Long.MAX_VALUE, 0), 524288.0, 0, 0);
        } catch (ArithmeticException expected) {
            thrown = true;
        }
        check(thrown, "cell overflow must be rejected");
    }

    private static void equal(long expected, long actual, String label) {
        check(expected == actual, label + ": expected " + expected + ", got " + actual);
    }

    private static void equal(double expected, double actual, String label) {
        check(Double.compare(expected, actual) == 0,
                label + ": expected " + expected + ", got " + actual);
    }

    private static void equal(String expected, String actual, String label) {
        check(expected.equals(actual), label + ": expected " + expected + ", got " + actual);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
