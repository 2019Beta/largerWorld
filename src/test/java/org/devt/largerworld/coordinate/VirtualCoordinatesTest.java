package org.devt.largerworld.coordinate;

import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import org.devt.largerworld.world.CellWorldKey;
import org.devt.largerworld.world.CellCreationLimits;
import org.devt.largerworld.world.ArbitraryPrecisionWorldgen;
import org.devt.largerworld.world.WorldgenCoordinates;

import java.math.BigDecimal;
import java.math.BigInteger;

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
        crossesBeyondLongCellRange();
        roundTripsCellWorldKeys();
        mapsNeighborChunksIntoClientView();
        keepsConnectionCoordinatesStableAcrossCrossing();
        mapsCrossCellVehicleMovementFromVehicleCell();
        identifiesCanonicalChunkBounds();
        mapsTransientRenderCenterWithoutCanonicalizing();
        foldsDistantWorldgenCoordinatesDeterministically();
        keepsHugeCellDeltasExact();
        makesWorldgenHighBitsNonPeriodic();
        keepsArbitraryPrecisionNoiseContinuousAcrossSeams();
        enforcesCellCreationLimits();
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

    private static void crossesBeyondLongCellRange() {
        BigInteger outsideLong = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE);
        VirtualPosition position = VirtualPosition.normalize(
                new CellPos(outsideLong, BigInteger.ZERO), 524288.0, 0, 0);
        equal(outsideLong.add(BigInteger.ONE), position.cell().x(),
                "cell coordinates extend beyond long");
        equal(-524288.0, position.localX(), "huge cell local normalization");
    }

    private static void roundTripsCellWorldKeys() {
        RegistryKey<World> base = RegistryKey.of(
                RegistryKeys.WORLD, Identifier.of("example", "nested/dimension"));
        CellPos cell = new CellPos(
                BigInteger.ONE.shiftLeft(200).negate().add(BigInteger.valueOf(17)),
                BigInteger.ONE.shiftLeft(160).add(BigInteger.valueOf(4815162342L)));
        RegistryKey<World> encoded = CellWorldKey.forCell(base, cell);
        CellWorldKey.Parsed parsed = CellWorldKey.parse(encoded).orElseThrow();
        check(parsed.baseWorld().equals(base), "base dimension key round trip");
        check(parsed.cell().equals(cell), "cell coordinate key round trip");
        check(CellWorldKey.forCell(base, CellPos.ZERO).equals(base), "zero cell uses canonical world");
    }

    private static void mapsNeighborChunksIntoClientView() {
        CellPos playerCell = new CellPos(100, -50);
        VirtualChunkPos east = new VirtualChunkPos(new CellPos(101, -50), -32768, 17);
        equal(32768, east.clientX(playerCell), "east neighbor client chunk");
        VirtualChunkPos decoded = VirtualChunkPos.fromClient(playerCell, 32768, 17);
        check(decoded.equals(east), "client chunk reverse mapping");

        VirtualChunkPos west = VirtualChunkPos.fromClient(playerCell, -32769, -4);
        check(west.cell().equals(new CellPos(99, -50)), "west neighbor cell");
        equal(32767, west.localX(), "west neighbor local chunk");
    }

    private static void keepsConnectionCoordinatesStableAcrossCrossing() {
        CellPos origin = new CellPos(7, -3);
        VirtualPosition target = VirtualPosition.normalize(origin, 524288.0, 80, -524288.25);
        check(target.cell().equals(new CellPos(8, -4)), "inbound coordinate resolves target cell");
        equal(-524288.0, target.localX(), "inbound coordinate resolves local X");
        equal(524287.75, target.localZ(), "inbound coordinate resolves local Z");

        double clientXAfterCrossing = target.localX()
                + target.cell().deltaXExact(origin) * (double) VirtualPosition.CELL_SIZE;
        double clientZAfterCrossing = target.localZ()
                + target.cell().deltaZExact(origin) * (double) VirtualPosition.CELL_SIZE;
        equal(524288.0, clientXAfterCrossing, "client X remains continuous");
        equal(-524288.25, clientZAfterCrossing, "client Z remains continuous");
    }

    private static void mapsCrossCellVehicleMovementFromVehicleCell() {
        CellPos origin = CellPos.ZERO;
        CellPos playerCell = new CellPos(1, 0);
        CellPos vehicleCell = CellPos.ZERO;
        double clientX = VirtualPosition.HALF_CELL + 0.25;

        double vehicleLocalX = VirtualPosition.clientToLocalX(vehicleCell, origin, clientX);
        equal(clientX, vehicleLocalX, "cross-cell vehicle uses vehicle cell");
        equal(-VirtualPosition.HALF_CELL + 0.25,
                VirtualPosition.clientToLocalX(playerCell, origin, clientX),
                "player cell would wrap vehicle to the wrong boundary");

        VirtualPosition normalized = VirtualPosition.normalize(
                vehicleCell, vehicleLocalX, 70, 0);
        check(normalized.cell().equals(playerCell),
                "positive-boundary vehicle must cross into the player's cell");
        equal(-VirtualPosition.HALF_CELL + 0.25, normalized.localX(),
                "positive-boundary vehicle local coordinate");
    }

    private static void identifiesCanonicalChunkBounds() {
        check(VirtualChunkPos.isCanonical(-32768, 32767), "chunk bounds are inclusive/exclusive");
        check(!VirtualChunkPos.isCanonical(32768, 0), "positive seam chunk belongs to next cell");
        check(!VirtualChunkPos.isCanonical(-32769, 0), "negative seam chunk belongs to previous cell");
    }

    private static void mapsTransientRenderCenterWithoutCanonicalizing() {
        equal(32768, VirtualChunkPos.toClientCoordinate(0, 0, 32768),
                "transient seam render center");
        equal(32768, VirtualChunkPos.toClientCoordinate(1, 0, -32768),
                "neighbor cell render center");
    }

    private static void foldsDistantWorldgenCoordinatesDeterministically() {
        CellPos cell = new CellPos(2098, 0);
        int foldedBlock = WorldgenCoordinates.toGlobalBlockX(cell, 87552);
        equal((int) 2_200_000_000L, foldedBlock, "distant block coordinate folds to int");

        ChunkPos foldedChunk = WorldgenCoordinates.toGlobalChunk(cell, new ChunkPos(5472, 0));
        equal(137_500_000, foldedChunk.x, "distant chunk coordinate");
        equal(foldedBlock, foldedChunk.getStartX(), "chunk and block folding agree");
        equal(87552, WorldgenCoordinates.toLocalBlock(
                cell, new net.minecraft.util.math.BlockPos(foldedBlock, 0, 0)).getX(),
                "folded block coordinate round trip");

        equal(-1_048_576,
                WorldgenCoordinates.toGlobalBlockX(new CellPos(Long.MAX_VALUE, 0), 0),
                "maximum cell worldgen remains representable");
    }

    private static void keepsHugeCellDeltasExact() {
        BigInteger huge = BigInteger.ONE.shiftLeft(180);
        CellPos origin = new CellPos(huge, huge.negate());
        CellPos neighbor = origin.add(1, -1);
        equal(-524288.0,
                VirtualPosition.clientToLocalX(neighbor, origin, 524288.0),
                "huge positive neighbor delta");
        equal(524288.0,
                VirtualPosition.clientToLocalZ(neighbor, origin, -524288.0),
                "huge negative neighbor delta");
        equal(32768,
                new VirtualChunkPos(neighbor, -32768, 32767).clientX(origin),
                "huge cell chunk mapping");
    }

    private static void makesWorldgenHighBitsNonPeriodic() {
        CellPos first = new CellPos(4096, 0);
        CellPos oldPeriod = new CellPos(4096 + 65536L, 0);
        ChunkPos a = WorldgenCoordinates.toRandomChunk(first, new ChunkPos(0, 0), 1234L);
        ChunkPos b = WorldgenCoordinates.toRandomChunk(oldPeriod, new ChunkPos(0, 0), 1234L);
        check(!a.equals(b), "full cell high bits must affect worldgen random tokens");

        double densityA = ArbitraryPrecisionWorldgen.densityOffset(first, 0, 0, 987654321L);
        double densityB = ArbitraryPrecisionWorldgen.densityOffset(oldPeriod, 0, 0, 987654321L);
        check(Double.compare(densityA, densityB) != 0,
                "arbitrary-precision density must not repeat at the old period");
    }

    private static void keepsArbitraryPrecisionNoiseContinuousAcrossSeams() {
        CellPos west = new CellPos(BigInteger.ONE.shiftLeft(100), BigInteger.ZERO);
        CellPos east = west.add(1, 0);
        double before = ArbitraryPrecisionWorldgen.densityOffset(
                west, (int) VirtualPosition.HALF_CELL - 1, 17, 42L);
        double after = ArbitraryPrecisionWorldgen.densityOffset(
                east, (int) -VirtualPosition.HALF_CELL, 17, 42L);
        check(Math.abs(after - before) < 0.002,
                "arbitrary-precision density must be continuous across a cell seam");
    }

    private static void enforcesCellCreationLimits() {
        CellCreationLimits limits = new CellCreationLimits(4, 2);
        check(limits.allows(3, 1), "limits allow a creation below both caps");
        check(!limits.allows(4, 0), "active cell cap is enforced");
        check(!limits.allows(0, 2), "per-tick creation cap is enforced");
    }

    private static void equal(long expected, long actual, String label) {
        check(expected == actual, label + ": expected " + expected + ", got " + actual);
    }

    private static void equal(BigInteger expected, BigInteger actual, String label) {
        check(expected.equals(actual), label + ": expected " + expected + ", got " + actual);
    }

    private static void equal(long expected, BigInteger actual, String label) {
        equal(BigInteger.valueOf(expected), actual, label);
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
