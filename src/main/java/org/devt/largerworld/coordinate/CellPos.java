package org.devt.largerworld.coordinate;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;

/** Identifies one 2^20 by 2^20 block coordinate cell. */
public record CellPos(long x, long z) {
    public static final CellPos ZERO = new CellPos(0, 0);

    public static final Codec<CellPos> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.LONG.fieldOf("x").forGetter(CellPos::x),
            Codec.LONG.fieldOf("z").forGetter(CellPos::z)
    ).apply(instance, CellPos::new));

    public static final PacketCodec<RegistryByteBuf, CellPos> PACKET_CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_LONG, CellPos::x,
            PacketCodecs.VAR_LONG, CellPos::z,
            CellPos::new
    );

    public CellPos add(long deltaX, long deltaZ) {
        return new CellPos(Math.addExact(x, deltaX), Math.addExact(z, deltaZ));
    }
}
