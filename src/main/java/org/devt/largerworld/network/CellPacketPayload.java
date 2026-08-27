package org.devt.largerworld.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.state.NetworkState;
import net.minecraft.network.state.PlayStateFactories;
import net.minecraft.util.Identifier;
import org.devt.largerworld.Largerworld;
import org.devt.largerworld.coordinate.CellPos;

/**
 * Carries one vanilla play packet together with the cell whose local
 * coordinates the packet uses. The client applies the enclosed packet to its
 * normal ClientWorld while a coordinate-translation context is active.
 */
public record CellPacketPayload(
        CellPos sourceCell,
        CellPos originCell,
        Packet<? super ClientPlayPacketListener> packet) implements CustomPayload {
    public static final Id<CellPacketPayload> ID =
            new Id<>(Identifier.of(Largerworld.MOD_ID, "cell_packet"));
    private static final int MAX_PACKET_SIZE = 8 * 1024 * 1024;
    private static final ThreadLocal<Boolean> ENCODING_PACKET =
            ThreadLocal.withInitial(() -> false);

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static final NetworkState<ClientPlayPacketListener> PLAY_PROTOCOL =
            PlayStateFactories.S2C.bind(buf -> (RegistryByteBuf) buf);

    public static final PacketCodec<RegistryByteBuf, CellPacketPayload> CODEC = PacketCodec.of(
            (payload, buf) -> {
                CellPos.PACKET_CODEC.encode(buf, payload.sourceCell());
                CellPos.PACKET_CODEC.encode(buf, payload.originCell());
                boolean previous = ENCODING_PACKET.get();
                ENCODING_PACKET.set(true);
                try {
                    PLAY_PROTOCOL.codec().encode(buf, (Packet) payload.packet());
                } finally {
                    if (previous) {
                        ENCODING_PACKET.set(true);
                    } else {
                        ENCODING_PACKET.remove();
                    }
                }
            },
            buf -> new CellPacketPayload(
                    CellPos.PACKET_CODEC.decode(buf),
                    CellPos.PACKET_CODEC.decode(buf),
                    PLAY_PROTOCOL.codec().decode(buf)));

    /**
     * Integrated singleplayer shares packet classes between client and server.
     * Client accessor mixins must not translate getters while the same packet is
     * being encoded on a re-entrant local-network call.
     */
    public static boolean isEncodingPacket() {
        return ENCODING_PACKET.get();
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().registerLarge(ID, CODEC, MAX_PACKET_SIZE);
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
