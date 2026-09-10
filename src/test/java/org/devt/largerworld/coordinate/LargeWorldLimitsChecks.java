package org.devt.largerworld.coordinate;

import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;
import net.minecraft.world.dimension.DimensionType;
import org.devt.largerworld.world.CellStorage;
import org.devt.largerworld.world.CellWorldKey;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;

/** Exercises actual packet codecs and the transformed save-directory entry point. */
public final class LargeWorldLimitsChecks {
    private LargeWorldLimitsChecks() {
    }

    public static void run() {
        packetRoundTripBeyondOldLimit();
        malformedPacketLengths();
        validatesExponentsBeforeExpansion();
        try {
            storageRoundTrip();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static RegistryByteBuf buffer() {
        return new RegistryByteBuf(Unpooled.buffer(), DynamicRegistryManager.EMPTY);
    }

    private static void packetRoundTripBeyondOldLimit() {
        RegistryByteBuf buf = buffer();
        try {
            CellPos value = new CellPos(BigInteger.ONE.shiftLeft(16384).add(BigInteger.ONE),
                    BigInteger.ONE.shiftLeft(8192).negate());
            CellPos.PACKET_CODEC.encode(buf, value);
            require(CellPos.PACKET_CODEC.decode(buf).equals(value), "large packet round trip");
            require(buf.readableBytes() == 0, "complete coordinate frame consumed");
        } finally {
            buf.release();
        }
    }

    private static void malformedPacketLengths() {
        for (int length : new int[] {0, -1, Integer.MAX_VALUE, 20}) {
            RegistryByteBuf buf = buffer();
            try {
                buf.writeVarInt(length);
                buf.writeByte(1);
                expectFailure(IllegalArgumentException.class,
                        () -> CellPos.PACKET_CODEC.decode(buf), "invalid length " + length);
            } finally {
                buf.release();
            }
        }
    }

    private static void validatesExponentsBeforeExpansion() {
        expectFailure(IllegalArgumentException.class,
                () -> GlobalCoordinateInput.parse("1e2147483647"), "huge exponent rejected");
        require(GlobalCoordinateInput.parse("1e-2147483647").signum() == 0,
                "unrepresentable fraction handled without expansion");
        require(GlobalCoordinateInput.parse("0e2147483647").signum() == 0, "extreme zero exponent");
        VirtualPosition position = VirtualPosition.fromGlobal(
                GlobalCoordinateInput.parse("1e5000"), 64, GlobalCoordinateInput.parse("-1e4000"));
        RegistryKey<World> key = CellWorldKey.forCell(World.OVERWORLD, position.cell());
        CellWorldKey.requireNetworkEncodable(key);
        require(position.globalX().compareTo(GlobalCoordinateInput.parse("1e5000")) == 0,
                "5000-digit command retains the complete coordinate");
        RegistryKey<World> oversized = CellWorldKey.forCell(World.OVERWORLD,
                new CellPos(BigInteger.TEN.pow(33000), BigInteger.ZERO));
        expectFailure(IllegalArgumentException.class,
                () -> CellWorldKey.requireNetworkEncodable(oversized), "vanilla identifier preflight");
    }

    private static void storageRoundTrip() throws IOException {
        Path testRoot = Path.of("build", "cell-storage-tests").toAbsolutePath();
        Files.createDirectories(testRoot);
        Path root = Files.createTempDirectory(testRoot, "save-");
        CellPos enormous = new CellPos(BigInteger.TEN.pow(5000), BigInteger.TEN.pow(4000).negate());
        RegistryKey<World> key = CellWorldKey.forCell(World.OVERWORLD, enormous);
        Path directory = DimensionType.getSaveDirectory(key, root);
        require(root.relativize(directory).toString().length() < 100, "fixed length disk address");
        require(Files.readString(directory.resolve("cell-key.txt")).equals(key.getValue().toString()),
                "manifest retains full identity");
        Files.writeString(directory.resolve("saved-marker"), "existing chunk data");
        require(DimensionType.getSaveDirectory(key, root).equals(directory), "reopen uses same directory");
        require(Files.readString(directory.resolve("saved-marker")).equals("existing chunk data"),
                "reopen retains stored data");
        require(!DimensionType.getSaveDirectory(CellWorldKey.forCell(World.NETHER, enormous), root)
                .equals(directory), "base dimensions have separate storage");
        require(DimensionType.getSaveDirectory(World.OVERWORLD, root).equals(root),
                "ordinary world directory unchanged");

        RegistryKey<World> legacyKey = CellWorldKey.forCell(World.OVERWORLD, new CellPos(1, -2));
        Path legacy = root.resolve("dimensions/largerworld").resolve(legacyKey.getValue().getPath());
        Files.createDirectories(legacy);
        Files.writeString(legacy.resolve("saved-marker"), "old data");
        require(DimensionType.getSaveDirectory(legacyKey, root).equals(legacy), "legacy save retained");

        Files.writeString(directory.resolve("cell-key.txt"), "wrong identity");
        expectFailure(UncheckedIOException.class, () -> DimensionType.getSaveDirectory(key, root),
                "manifest mismatch fails closed");
        Path ambiguous = CellStorage.compactDirectory(root, legacyKey.getValue().toString());
        Files.createDirectories(ambiguous);
        expectFailure(UncheckedIOException.class, () -> DimensionType.getSaveDirectory(legacyKey, root),
                "ambiguous legacy and compact data must not be silently selected");
    }

    private static void expectFailure(Class<? extends Throwable> type, Runnable action, String label) {
        try {
            action.run();
        } catch (Throwable failure) {
            if (type.isInstance(failure)) {
                return;
            }
            throw new AssertionError(label, failure);
        }
        throw new AssertionError(label + ": expected " + type.getSimpleName());
    }

    private static void require(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError(label);
        }
    }
}
