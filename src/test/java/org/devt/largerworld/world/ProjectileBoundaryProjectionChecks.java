package org.devt.largerworld.world;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.devt.largerworld.coordinate.VirtualPosition;

import java.util.Optional;

/** Focused geometry checks for projected boxes and source-coordinate ray hits. */
public final class ProjectileBoundaryProjectionChecks {
    private static final double HALF_CELL = VirtualPosition.HALF_CELL;

    private ProjectileBoundaryProjectionChecks() {
    }

    public static void run() {
        translatesPositiveXBoundary();
        translatesNegativeXBoundary();
        translatesPositiveZBoundary();
        translatesNegativeZBoundary();
        translatesDiagonalBoundary();
        leavesSameCellBoxUnchanged();
        rayHitPositionStaysInSourceCoordinates();
    }

    private static void translatesPositiveXBoundary() {
        checkTranslation(-HALF_CELL, 19, HALF_CELL, 19, "positive X seam");
    }

    private static void translatesNegativeXBoundary() {
        checkTranslation(HALF_CELL - 1, 19, -HALF_CELL - 1, 19, "negative X seam");
    }

    private static void translatesPositiveZBoundary() {
        checkTranslation(23, -HALF_CELL, 23, HALF_CELL, "positive Z seam");
    }

    private static void translatesNegativeZBoundary() {
        checkTranslation(23, HALF_CELL - 1, 23, -HALF_CELL - 1, "negative Z seam");
    }

    private static void translatesDiagonalBoundary() {
        checkTranslation(-HALF_CELL, HALF_CELL - 1, HALF_CELL, -HALF_CELL - 1,
                "diagonal positive X negative Z seam");
        checkTranslation(-HALF_CELL, -HALF_CELL, HALF_CELL, HALF_CELL,
                "diagonal positive X positive Z seam");
        checkTranslation(HALF_CELL - 1, -HALF_CELL, -HALF_CELL - 1, HALF_CELL,
                "diagonal negative X positive Z seam");
        checkTranslation(HALF_CELL - 1, HALF_CELL - 1, -HALF_CELL - 1, -HALF_CELL - 1,
                "diagonal negative X negative Z seam");
    }

    private static void leavesSameCellBoxUnchanged() {
        Vec3d position = new Vec3d(7, 64, -11);
        Box local = new Box(6.7, 63.5, -11.3, 7.3, 64.5, -10.7);
        Box projected = ProjectileBoundaryProjection.translateBox(local, position, position);
        equal(local.minX, projected.minX, "same-cell minX");
        equal(local.minY, projected.minY, "same-cell minY");
        equal(local.minZ, projected.minZ, "same-cell minZ");
        equal(local.maxX, projected.maxX, "same-cell maxX");
        equal(local.maxY, projected.maxY, "same-cell maxY");
        equal(local.maxZ, projected.maxZ, "same-cell maxZ");
    }

    private static void rayHitPositionStaysInSourceCoordinates() {
        Vec3d localPosition = new Vec3d(-HALF_CELL, 65, 0);
        Vec3d projectedPosition = new Vec3d(HALF_CELL, 65, 0);
        Box localBox = new Box(-HALF_CELL - 0.3, 64, -0.3,
                -HALF_CELL + 0.3, 66, 0.3);
        Box sourceBox = ProjectileBoundaryProjection.translateBox(
                localBox, localPosition, projectedPosition);
        Vec3d start = new Vec3d(HALF_CELL - 1, 65, 0);
        Vec3d end = new Vec3d(HALF_CELL + 1, 65, 0);
        Optional<Vec3d> hit = sourceBox.raycast(start, end);
        check(hit.isPresent(), "projected seam box missed source ray");
        equal(HALF_CELL - 0.3, hit.orElseThrow().x, "source-coordinate ray hit X");
        equal(65, hit.orElseThrow().y, "source-coordinate ray hit Y");
    }

    private static void checkTranslation(
            double localX, double localZ, double projectedX, double projectedZ, String label) {
        Vec3d localPosition = new Vec3d(localX, 64, localZ);
        Vec3d projectedPosition = new Vec3d(projectedX, 64, projectedZ);
        Box local = new Box(localX - 0.25, 63.5, localZ - 0.25,
                localX + 0.25, 64.5, localZ + 0.25);
        Box projected = ProjectileBoundaryProjection.translateBox(
                local, localPosition, projectedPosition);
        equal(projectedX - 0.25, projected.minX, label + " minX");
        equal(projectedZ - 0.25, projected.minZ, label + " minZ");
        equal(projectedX + 0.25, projected.maxX, label + " maxX");
        equal(projectedZ + 0.25, projected.maxZ, label + " maxZ");
    }

    private static void equal(double expected, double actual, String label) {
        if (Math.abs(expected - actual) > 1.0e-9) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static void check(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError(label);
        }
    }
}
