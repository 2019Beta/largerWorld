package org.devt.largerworld.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import org.devt.largerworld.Largerworld;

/** Marks the ordered packet window in which a ridden entity changes cell worlds. */
public record EntityHandoffPayload(int entityId, boolean begin) implements CustomPayload {
    public static final Id<EntityHandoffPayload> ID =
            new Id<>(Identifier.of(Largerworld.MOD_ID, "entity_handoff"));

    public static final PacketCodec<RegistryByteBuf, EntityHandoffPayload> CODEC = PacketCodec.of(
            (payload, buf) -> {
                buf.writeVarInt(payload.entityId());
                buf.writeBoolean(payload.begin());
            },
            buf -> new EntityHandoffPayload(buf.readVarInt(), buf.readBoolean()));

    public static void register() {
        PayloadTypeRegistry.playS2C().register(ID, CODEC);
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
