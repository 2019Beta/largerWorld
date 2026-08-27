package org.devt.largerworld.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import org.devt.largerworld.Largerworld;

import java.util.UUID;

/** Announces that an existing client entity will be replaced server-side at a cell seam. */
public record EntityHandoffPayload(int entityId, UUID entityUuid) implements CustomPayload {
    public static final Id<EntityHandoffPayload> ID =
            new Id<>(Identifier.of(Largerworld.MOD_ID, "entity_handoff"));

    public static final PacketCodec<RegistryByteBuf, EntityHandoffPayload> CODEC = PacketCodec.of(
            (payload, buf) -> {
                buf.writeVarInt(payload.entityId());
                buf.writeUuid(payload.entityUuid());
            },
            buf -> new EntityHandoffPayload(buf.readVarInt(), buf.readUuid()));

    public static void register() {
        PayloadTypeRegistry.playS2C().register(ID, CODEC);
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
