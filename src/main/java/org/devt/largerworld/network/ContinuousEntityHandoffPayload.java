package org.devt.largerworld.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import org.devt.largerworld.Largerworld;
import org.devt.largerworld.coordinate.CellPos;

import java.util.UUID;

/** Keeps one non-player client entity alive while its server tracker changes cells. */
public record ContinuousEntityHandoffPayload(
        Phase phase,
        int entityId,
        UUID entityUuid,
        CellPos sourceCell,
        CellPos targetCell) implements CustomPayload {
    public enum Phase {
        BEGIN,
        ABORT
    }

    public static final Id<ContinuousEntityHandoffPayload> ID =
            new Id<>(Identifier.of(Largerworld.MOD_ID, "continuous_entity_handoff"));

    public static final PacketCodec<RegistryByteBuf, ContinuousEntityHandoffPayload> CODEC =
            PacketCodec.of(
                    (payload, buf) -> {
                        buf.writeEnumConstant(payload.phase());
                        buf.writeVarInt(payload.entityId());
                        buf.writeUuid(payload.entityUuid());
                        CellPos.PACKET_CODEC.encode(buf, payload.sourceCell());
                        CellPos.PACKET_CODEC.encode(buf, payload.targetCell());
                    },
                    buf -> new ContinuousEntityHandoffPayload(
                            buf.readEnumConstant(Phase.class),
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
