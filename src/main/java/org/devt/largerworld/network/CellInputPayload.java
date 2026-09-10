package org.devt.largerworld.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.listener.ServerPlayPacketListener;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.*;
import net.minecraft.network.state.NetworkState;
import net.minecraft.network.state.PlayStateFactories;
import net.minecraft.util.Identifier;
import org.devt.largerworld.coordinate.CellPos;

/** Input coordinates retain their meaning even if a rebase overtakes the packet. */
public record CellInputPayload(CellPos origin, Packet<? super ServerPlayPacketListener> packet)
        implements CustomPayload {
    public static final Id<CellInputPayload> ID =
            new Id<>(Identifier.of("largerworld", "cell_input"));
    private static final NetworkState<ServerPlayPacketListener> PLAY =
            PlayStateFactories.C2S.bind(buf -> (RegistryByteBuf) buf, () -> false);
    private static final ThreadLocal<Boolean> DECODING = ThreadLocal.withInitial(() -> false);

    public static final PacketCodec<RegistryByteBuf, CellInputPayload> CODEC = PacketCodec.of(
            (payload, buf) -> {
                requireCoordinatePacket(payload.packet());
                CellPos.PACKET_CODEC.encode(buf, payload.origin());
                PLAY.codec().encode(buf, payload.packet());
            }, buf -> {
                if (DECODING.get()) {
                    throw new IllegalArgumentException("Nested cell input frames are not allowed");
                }
                DECODING.set(true);
                try {
                    CellPos origin = CellPos.PACKET_CODEC.decode(buf);
                    Packet<? super ServerPlayPacketListener> packet = PLAY.codec().decode(buf);
                    requireCoordinatePacket(packet);
                    return new CellInputPayload(origin, packet);
                } finally {
                    DECODING.remove();
                }
            });

    public static boolean isCoordinatePacket(Packet<?> packet) {
        return packet instanceof PlayerMoveC2SPacket || packet instanceof VehicleMoveC2SPacket
                || packet instanceof PlayerActionC2SPacket || packet instanceof PlayerInteractBlockC2SPacket
                || packet instanceof PlayerInteractEntityC2SPacket || packet instanceof PickItemFromBlockC2SPacket
                || packet instanceof JigsawGeneratingC2SPacket || packet instanceof SetTestBlockC2SPacket
                || packet instanceof TestInstanceBlockActionC2SPacket || packet instanceof UpdateCommandBlockC2SPacket
                || packet instanceof UpdateJigsawC2SPacket || packet instanceof UpdateSignC2SPacket
                || packet instanceof UpdateStructureBlockC2SPacket;
    }

    private static void requireCoordinatePacket(Packet<?> packet) {
        if (!isCoordinatePacket(packet)) {
            throw new IllegalArgumentException("Unsupported enclosed input packet");
        }
    }

    public static void register() {
        PayloadTypeRegistry.playC2S().registerLarge(ID, CODEC, 1024 * 1024);
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
