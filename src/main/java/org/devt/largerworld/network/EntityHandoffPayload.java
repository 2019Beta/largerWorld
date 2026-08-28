package org.devt.largerworld.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import org.devt.largerworld.Largerworld;
import org.devt.largerworld.coordinate.CellPos;

import java.util.UUID;

/** Bridges one player-ridden entity graph between source and target trackers. */
public record EntityHandoffPayload(
        int entityId,
        UUID entityUuid,
        CellPos sourceCell,
        CellPos targetCell) implements CustomPayload {
    public static final Id<EntityHandoffPayload> ID =
            new Id<>(Identifier.of(Largerworld.MOD_ID, "entity_handoff"));

    public static final PacketCodec<RegistryByteBuf, EntityHandoffPayload> CODEC = PacketCodec.of(
            (payload, buf) -> {
                buf.writeVarInt(payload.entityId());
                buf.writeUuid(payload.entityUuid());
                CellPos.PACKET_CODEC.encode(buf, payload.sourceCell());
                CellPos.PACKET_CODEC.encode(buf, payload.targetCell());
            },
            buf -> new EntityHandoffPayload(
                    buf.readVarInt(),
                    buf.readUuid(),
                    CellPos.PACKET_CODEC.decode(buf),
                    CellPos.PACKET_CODEC.decode(buf)));

    public static void register() {
        PayloadTypeRegistry.playS2C().register(ID, CODEC);
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
