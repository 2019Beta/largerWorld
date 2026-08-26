package org.devt.largerworld.server;

import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.common.CustomPayloadS2CPacket;
import net.minecraft.network.packet.s2c.play.UnloadChunkS2CPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.devt.largerworld.coordinate.CellPos;
import org.devt.largerworld.Largerworld;
import org.devt.largerworld.coordinate.VirtualPosition;
import org.devt.largerworld.network.CellPacketPayload;
import org.devt.largerworld.world.CellWorldKey;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Map;
import java.util.WeakHashMap;

/** Server-side packet source tagging and per-connection floating origin. */
public final class CellPacketRouting {
    private static final ThreadLocal<CellPos> ACTIVE_SOURCE = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> DIRECT_UNLOAD = ThreadLocal.withInitial(() -> false);
    private static final Map<ServerPlayerEntity, CellPos> ORIGINS = new WeakHashMap<>();

    private CellPacketRouting() {
    }

    public static synchronized CellPos origin(ServerPlayerEntity player) {
        return ORIGINS.computeIfAbsent(player, ignored -> logicalCell(player));
    }

    public static void forget(ServerPlayerEntity player) {
        synchronized (CellPacketRouting.class) {
            ORIGINS.remove(player);
        }
    }

    public static Packet<?> wrap(ServerPlayNetworkHandler handler, Packet<?> packet) {
        if (packet.transitionsNetworkState()) {
            return packet;
        }
        if (packet instanceof CustomPayloadS2CPacket custom
                && custom.payload() instanceof CellPacketPayload) {
            return packet;
        }

        CellPos source = ACTIVE_SOURCE.get();
        if (source == null) {
            source = logicalCell(handler.player);
        }
        if (!DIRECT_UNLOAD.get()
                && packet instanceof UnloadChunkS2CPacket unload
                && CellViewTracker.shouldRetain(handler.player, source, unload.pos())) {
            return null;
        }
        return new CustomPayloadS2CPacket(new CellPacketPayload(source, origin(handler.player), cast(packet)));
    }

    @SuppressWarnings("unchecked")
    private static Packet<? super net.minecraft.network.listener.ClientPlayPacketListener> cast(Packet<?> packet) {
        return (Packet<? super net.minecraft.network.listener.ClientPlayPacketListener>) packet;
    }

    public static void sendFrom(ServerPlayerEntity player, ServerWorld sourceWorld, Packet<?> packet) {
        withSource(sourceWorld, () -> player.networkHandler.sendPacket(packet));
    }

    public static void sendUnload(ServerPlayerEntity player, ServerWorld sourceWorld, Packet<?> packet) {
        boolean previous = DIRECT_UNLOAD.get();
        DIRECT_UNLOAD.set(true);
        try {
            sendFrom(player, sourceWorld, packet);
        } finally {
            DIRECT_UNLOAD.set(previous);
        }
    }

    public static void withSource(ServerWorld world, Runnable action) {
        CellPos previous = ACTIVE_SOURCE.get();
        ACTIVE_SOURCE.set(CellWorldKey.cell(world.getRegistryKey()));
        try {
            action.run();
        } finally {
            if (previous == null) {
                ACTIVE_SOURCE.remove();
            } else {
                ACTIVE_SOURCE.set(previous);
            }
        }
    }

    public static void enterSource(ServerWorld world) {
        ACTIVE_SOURCE.set(CellWorldKey.cell(world.getRegistryKey()));
    }

    public static void leaveSource() {
        ACTIVE_SOURCE.remove();
    }

    public static double clientToLocalX(ServerPlayerEntity player, double clientX) {
        CellPos current = logicalCell(player);
        return clientX - (current.x() - origin(player).x()) * (double) VirtualPosition.CELL_SIZE;
    }

    private static CellPos logicalCell(ServerPlayerEntity player) {
        CellPos worldCell = CellWorldKey.cell(player.getEntityWorld().getRegistryKey());
        CellPos attached = player.getAttachedOrCreate(Largerworld.CELL_POS);
        return worldCell.equals(CellPos.ZERO) && !attached.equals(CellPos.ZERO)
                ? attached
                : worldCell;
    }

    public static double clientToLocalZ(ServerPlayerEntity player, double clientZ) {
        CellPos current = logicalCell(player);
        return clientZ - (current.z() - origin(player).z()) * (double) VirtualPosition.CELL_SIZE;
    }

    public static BlockPos clientToLocal(ServerPlayerEntity player, BlockPos clientPos) {
        return new BlockPos(
                Math.toIntExact(Math.round(clientToLocalX(player, clientPos.getX()))),
                clientPos.getY(),
                Math.toIntExact(Math.round(clientToLocalZ(player, clientPos.getZ()))));
    }

    public static Vec3d clientToLocal(ServerPlayerEntity player, Vec3d clientPos) {
        return new Vec3d(
                clientToLocalX(player, clientPos.x),
                clientPos.y,
                clientToLocalZ(player, clientPos.z));
    }

    public static BlockHitResult clientToLocal(ServerPlayerEntity player, BlockHitResult clientHit) {
        return new BlockHitResult(
                clientToLocal(player, clientHit.getPos()),
                clientHit.getSide(),
                clientToLocal(player, clientHit.getBlockPos()),
                clientHit.isInsideBlock(),
                clientHit.isAgainstWorldBorder());
    }
}
