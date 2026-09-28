package org.devt.largerworld.server;

import net.minecraft.util.math.ChunkPos;
import org.devt.largerworld.coordinate.CellPos;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Pure planner/refcount checks for the independently managed cell simulation tickets. */
public final class CellSimulationTrackerChecks {
    private CellSimulationTrackerChecks() {
    }

    public static void run() {
        plansPositiveBoundaryChunks();
        plansNegativeBoundaryChunks();
        plansOnlyTheThreeCellsAtACorner();
        staysFastAndEmptyFarFromBoundaries();
        sharesAndReleasesTicketsByReferenceCount();
    }

    private static void plansPositiveBoundaryChunks() {
        CellPos current = new CellPos(9, -4);
        Map<CellPos, Set<Long>> plan = CellSimulationTracker.planCanonicalChunks(
                current, 32767, 0, 2);
        Set<Long> east = plan.get(current.add(1, 0));
        check(plan.size() == 1 && east != null, "positive X seam plans only the east cell");
        check(east.size() == 10, "positive seam clips to two by five canonical chunks");
        for (long packed : east) {
            ChunkPos chunk = new ChunkPos(packed);
            check(chunk.x == -32768 || chunk.x == -32767,
                    "east ticket center uses canonical target X");
            check(chunk.z >= -2 && chunk.z <= 2,
                    "east ticket center preserves the projected Z range");
        }
    }

    private static void plansNegativeBoundaryChunks() {
        CellPos current = new CellPos(-3, 12);
        Map<CellPos, Set<Long>> plan = CellSimulationTracker.planCanonicalChunks(
                current, -32768, 0, 2);
        Set<Long> west = plan.get(current.add(-1, 0));
        check(plan.size() == 1 && west != null, "negative X seam plans only the west cell");
        check(west.size() == 10, "negative seam clips to two by five canonical chunks");
        for (long packed : west) {
            ChunkPos chunk = new ChunkPos(packed);
            check(chunk.x == 32766 || chunk.x == 32767,
                    "west ticket center uses canonical target X");
            check(chunk.z >= -2 && chunk.z <= 2,
                    "west ticket center preserves the projected Z range");
        }
    }

    private static void plansOnlyTheThreeCellsAtACorner() {
        CellPos current = new CellPos(20, -31);
        Map<CellPos, Set<Long>> plan = CellSimulationTracker.planCanonicalChunks(
                current, 32767, -32768, 1);
        check(plan.size() == 3, "corner footprint reaches two side cells and the diagonal");
        check(plan.get(current.add(1, 0)).size() == 2, "east side is clipped by the corner");
        check(plan.get(current.add(0, -1)).size() == 2, "south side is clipped by the corner");
        check(plan.get(current.add(1, -1)).size() == 1, "diagonal contributes one canonical chunk");
        for (Set<Long> chunks : plan.values()) {
            for (long packed : chunks) {
                ChunkPos chunk = new ChunkPos(packed);
                check(chunk.x >= -32768 && chunk.x <= 32767
                                && chunk.z >= -32768 && chunk.z <= 32767,
                        "all ticket centers stay inside target canonical bounds");
            }
        }
    }

    private static void staysFastAndEmptyFarFromBoundaries() {
        Map<CellPos, Set<Long>> plan = CellSimulationTracker.planCanonicalChunks(
                new CellPos(0, 0), 0, 0, 10);
        check(plan.isEmpty(), "ordinary interior simulation does not create neighbor tickets");
    }

    private static void sharesAndReleasesTicketsByReferenceCount() {
        Map<String, Integer> references = new HashMap<>();
        check(CellSimulationTracker.addReference(references, "cell/chunk"),
                "first player installs the underlying ticket");
        check(!CellSimulationTracker.addReference(references, "cell/chunk"),
                "second player shares the same ticket");
        check(!CellSimulationTracker.removeReference(references, "cell/chunk"),
                "first departure retains the shared ticket");
        check(CellSimulationTracker.removeReference(references, "cell/chunk"),
                "last departure releases the underlying ticket");
        check(references.isEmpty(), "last release clears the reference entry");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
