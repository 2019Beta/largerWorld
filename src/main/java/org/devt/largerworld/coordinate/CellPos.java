package org.devt.largerworld.coordinate;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.mojang.datafixers.util.Either;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;

import java.math.BigInteger;

/** Identifies one 2^20 by 2^20 block coordinate cell. */
public record CellPos(BigInteger x, BigInteger z) {
    /** Prevents a malicious packet from allocating an unbounded BigInteger. */
    public static final int MAX_NETWORK_BYTES = 512;
    public static final CellPos ZERO = new CellPos(BigInteger.ZERO, BigInteger.ZERO);

    private static final Codec<BigInteger> BIG_INTEGER_CODEC = Codec.either(
            Codec.LONG,
            Codec.STRING.comapFlatMap(
                    value -> {
                        try {
                            return DataResult.success(new BigInteger(value));
                        } catch (NumberFormatException exception) {
                            return DataResult.error(() -> "Invalid arbitrary-precision integer: " + value);
                        }
                    },
                    BigInteger::toString))
            .xmap(
                    value -> value.map(BigInteger::valueOf, coordinate -> coordinate),
                    value -> value.bitLength() < Long.SIZE
                            ? Either.left(value.longValue())
                            : Either.right(value));

    private static final PacketCodec<RegistryByteBuf, BigInteger> BIG_INTEGER_PACKET_CODEC =
            PacketCodec.of(
                    (value, buf) -> {
                        byte[] encoded = value.toByteArray();
                        if (encoded.length > MAX_NETWORK_BYTES) {
                            throw new IllegalArgumentException(
                                    "Cell coordinate exceeds network limit of "
                                            + MAX_NETWORK_BYTES + " bytes");
                        }
                        buf.writeByteArray(encoded);
                    },
                    buf -> {
                        byte[] encoded = buf.readByteArray(MAX_NETWORK_BYTES);
                        if (encoded.length == 0) {
                            throw new IllegalArgumentException("Empty cell coordinate");
                        }
                        return new BigInteger(encoded);
                    });

    public static final Codec<CellPos> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            BIG_INTEGER_CODEC.fieldOf("x").forGetter(CellPos::x),
            BIG_INTEGER_CODEC.fieldOf("z").forGetter(CellPos::z)
    ).apply(instance, CellPos::new));

    public static final PacketCodec<RegistryByteBuf, CellPos> PACKET_CODEC = PacketCodec.tuple(
            BIG_INTEGER_PACKET_CODEC, CellPos::x,
            BIG_INTEGER_PACKET_CODEC, CellPos::z,
            CellPos::new
    );

    public CellPos {
        if (x == null || z == null) {
            throw new NullPointerException("Cell coordinates");
        }
    }

    public CellPos(long x, long z) {
        this(BigInteger.valueOf(x), BigInteger.valueOf(z));
    }

    public CellPos add(long deltaX, long deltaZ) {
        return new CellPos(
                x.add(BigInteger.valueOf(deltaX)),
                z.add(BigInteger.valueOf(deltaZ)));
    }

    public BigInteger deltaX(CellPos origin) {
        return x.subtract(origin.x);
    }

    public BigInteger deltaZ(CellPos origin) {
        return z.subtract(origin.z);
    }

    /** Exact relative cell delta for operations already bounded to the active client window. */
    public long deltaXExact(CellPos origin) {
        return deltaX(origin).longValueExact();
    }

    /** Exact relative cell delta for operations already bounded to the active client window. */
    public long deltaZExact(CellPos origin) {
        return deltaZ(origin).longValueExact();
    }

    public boolean isWithin(CellPos origin, long maxDelta) {
        BigInteger limit = BigInteger.valueOf(maxDelta);
        return deltaX(origin).abs().compareTo(limit) <= 0
                && deltaZ(origin).abs().compareTo(limit) <= 0;
    }
}
