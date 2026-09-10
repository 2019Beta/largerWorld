package org.devt.largerworld.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import org.devt.largerworld.coordinate.CellPos;

/** Ordered before packets using the new connection origin; no dimension respawn. */
public record OriginRebasePayload(CellPos previous, CellPos next) implements CustomPayload {
    public static final Id<OriginRebasePayload> ID =
            new Id<>(Identifier.of("largerworld", "origin_rebase"));
    public static final PacketCodec<RegistryByteBuf, OriginRebasePayload> CODEC = PacketCodec.tuple(
            CellPos.PACKET_CODEC, OriginRebasePayload::previous,
            CellPos.PACKET_CODEC, OriginRebasePayload::next, OriginRebasePayload::new);

    public static void register() {
        PayloadTypeRegistry.playS2C().registerLarge(ID, CODEC, 1024 * 1024);
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
