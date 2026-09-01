package org.devt.largerworld.server;

import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.common.CustomPayloadS2CPacket;
import net.minecraft.network.packet.s2c.play.BundleS2CPacket;
import net.minecraft.network.packet.s2c.play.GameJoinS2CPacket;
import net.minecraft.network.packet.s2c.play.UnloadChunkS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.LightUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkBiomeDataS2CPacket;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import org.devt.largerworld.coordinate.CellPos;
import org.devt.largerworld.Largerworld;
import org.devt.largerworld.coordinate.VirtualPosition;
import org.devt.largerworld.coordinate.VirtualChunkPos;
import org.devt.largerworld.network.CellPacketPayload;
import org.devt.largerworld.network.ContinuousEntityHandoffPayload;
import org.devt.largerworld.network.EntityHandoffPayload;
import org.devt.largerworld.world.CellWorldKey;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;

import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;
import java.util.function.Supplier;

/** Server-side packet source tagging and per-connection floating origin. */
public final class CellPacketRouting {
    /**
     * Keep an entire cell inside vanilla's valid horizontal block range. Crossing
     * this threshold requires a client-world reload with a fresh network origin.
     */
    private static final double CLIENT_ORIGIN_REBASE_LIMIT =
            World.HORIZONTAL_LIMIT - (double) VirtualPosition.HALF_CELL;
    private static final long MAX_CLIENT_CELL_DELTA =
            (long) ((CLIENT_ORIGIN_REBASE_LIMIT - 1.0) / VirtualPosition.CELL_SIZE);
    private static final ThreadLocal<CellPos> ACTIVE_SOURCE = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> DIRECT_UNLOAD = ThreadLocal.withInitial(() -> false);
    private static final Map<ServerPlayerEntity, CellPos> ORIGINS = new WeakHashMap<>();

    private CellPacketRouting() {
    }

    public static synchronized CellPos origin(ServerPlayerEntity player) {
        return ORIGINS.computeIfAbsent(player, ignored -> currentWorldCell(player));
    }

    public static void forget(ServerPlayerEntity player) {
        synchronized (CellPacketRouting.class) {
            ORIGINS.remove(player);
        }
    }

    public static synchronized void clearServerState() {
        ORIGINS.clear();
        ACTIVE_SOURCE.remove();
        DIRECT_UNLOAD.remove();
    }

    /**
     * Moves the per-connection network origin when the target cell can no longer
     * be represented safely by vanilla client coordinates.
     *
     * @return {@code true} when the caller must use a full vanilla world change
     *         instead of the seamless same-client-world transition
     */
    public static synchronized boolean rebaseForDistantTeleport(
            ServerPlayerEntity player, CellPos targetCell) {
        CellPos currentOrigin = origin(player);
        if (!requiresOriginRebase(currentOrigin, targetCell)) {
            return false;
        }

        ORIGINS.put(player, targetCell);
        Largerworld.logEntityInfo(
                "Rebased client origin for {} from cell [{}, {}] to [{}, {}]",
                player.getName().getString(),
                currentOrigin.x(), currentOrigin.z(), targetCell.x(), targetCell.z());
        return true;
    }

    /** Returns whether a target cell needs a vanilla client-world reload. */
    public static synchronized boolean requiresOriginRebase(
            ServerPlayerEntity player, CellPos targetCell) {
        return requiresOriginRebase(origin(player), targetCell);
    }

    private static boolean requiresOriginRebase(CellPos currentOrigin, CellPos targetCell) {
        return !targetCell.isWithin(currentOrigin, MAX_CLIENT_CELL_DELTA);
    }

    public static Packet<?> wrap(ServerPlayNetworkHandler handler, Packet<?> packet) {
        // GameJoin creates the client's play network handler. A custom play
        // payload is delivered before that handler exists, so forwarding an
        // enclosed GameJoin packet would apply it to a null listener.
        if (packet.transitionsNetworkState() || packet instanceof GameJoinS2CPacket) {
            return packet;
        }
        if (packet instanceof CustomPayloadS2CPacket custom
                && (custom.payload() instanceof CellPacketPayload
                || custom.payload() instanceof EntityHandoffPayload
                || custom.payload() instanceof ContinuousEntityHandoffPayload)) {
            return packet;
        }
        // Bundle packets are synthetic transport containers and are not part of
        // the normal play-state packet codec. Wrapping the container itself in a
        // CellPacketPayload therefore fails with "Sending unknown packet ... bundle".
        // Preserve vanilla bundling while tagging each encodable child packet.
        if (packet instanceof BundleS2CPacket bundle) {
            List<Packet<? super ClientPlayPacketListener>> wrappedPackets = new ArrayList<>();
            boolean changed = false;
            for (Packet<? super ClientPlayPacketListener> child : bundle.getPackets()) {
                Packet<?> wrapped = wrap(handler, child);
                if (wrapped == null) {
                    changed = true;
                } else {
                    wrappedPackets.add(cast(wrapped));
                    changed |= wrapped != child;
                }
            }
            return changed ? new BundleS2CPacket(wrappedPackets) : packet;
        }

        CellPos source = ACTIVE_SOURCE.get();
        if (source == null) {
            source = currentWorldCell(handler.player);
        }
        // After a distant-origin rebase, late packets from the abandoned client
        // window must not be translated through BlockPos/ChunkPos. They describe
        // state that the respawn reload has already discarded and may exceed int.
        if (!isCellInsideClientWindow(source, origin(handler.player))) {
            return null;
        }
        if (packet instanceof ChunkDataS2CPacket chunk
                && !VirtualChunkPos.isCanonical(chunk.getChunkX(), chunk.getChunkZ())) {
            return null;
        }
        if (packet instanceof ChunkDataS2CPacket chunk) {
            ChunkPos pos = new ChunkPos(chunk.getChunkX(), chunk.getChunkZ());
            boolean suppressed = CellViewTracker.shouldSuppressHandoffChunk(
                    handler.player, source, pos);
            if (suppressed) {
                return null;
            }
        }
        if (packet instanceof LightUpdateS2CPacket light
                && !VirtualChunkPos.isCanonical(light.getChunkX(), light.getChunkZ())) {
            return null;
        }
        if (packet instanceof ChunkBiomeDataS2CPacket biomes) {
            List<ChunkBiomeDataS2CPacket.Serialized> filtered = biomes.chunkBiomeData().stream()
                    .filter(data -> VirtualChunkPos.isCanonical(data.pos().x, data.pos().z))
                    .toList();
            if (filtered.isEmpty()) {
                return null;
            }
            if (filtered.size() != biomes.chunkBiomeData().size()) {
                packet = new ChunkBiomeDataS2CPacket(filtered);
            }
        }
        if (packet instanceof UnloadChunkS2CPacket unload
                && !VirtualChunkPos.isCanonical(unload.pos().x, unload.pos().z)) {
            return null;
        }
        if (!DIRECT_UNLOAD.get() && packet instanceof UnloadChunkS2CPacket unload) {
            Boolean handoffDecision = CellViewTracker.shouldSuppressHandoffUnload(
                    handler.player, source, unload.pos());
            boolean suppressed = handoffDecision != null
                    ? handoffDecision
                    : CellViewTracker.shouldRetain(handler.player, source, unload.pos());
            if (suppressed) {
                return null;
            }
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

    public static <T> T withSourceResult(ServerWorld world, Supplier<T> action) {
        CellPos previous = ACTIVE_SOURCE.get();
        ACTIVE_SOURCE.set(CellWorldKey.cell(world.getRegistryKey()));
        try {
            return action.get();
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
        return clientToLocalX(player, currentWorldCell(player), clientX);
    }

    /**
     * Converts a connection-space X coordinate for an entity in {@code sourceCell}.
     *
     * <p>The source is normally the player's current cell. Controlled vehicles can
     * briefly remain in the neighboring cell after a cross-cell mount, so their
     * movement packets must use the vehicle's real world instead.</p>
     */
    public static double clientToLocalX(
            ServerPlayerEntity player, CellPos sourceCell, double clientX) {
        return VirtualPosition.clientToLocalX(sourceCell, origin(player), clientX);
    }

    /**
     * Returns the cell that is actually producing or consuming packets now.
     *
     * <p>The CELL_POS attachment is persistent state used to restore a player
     * after login. During a cross-world teleport it can briefly differ from the
     * entity's real world, so it must never be used as a live packet source.</p>
     */
    private static CellPos currentWorldCell(ServerPlayerEntity player) {
        return CellWorldKey.cell(player.getEntityWorld().getRegistryKey());
    }

    private static boolean isCellInsideClientWindow(CellPos cell, CellPos clientOrigin) {
        return cell.isWithin(clientOrigin, MAX_CLIENT_CELL_DELTA);
    }

    public static double clientToLocalZ(ServerPlayerEntity player, double clientZ) {
        return clientToLocalZ(player, currentWorldCell(player), clientZ);
    }

    /** See {@link #clientToLocalX(ServerPlayerEntity, CellPos, double)}. */
    public static double clientToLocalZ(
            ServerPlayerEntity player, CellPos sourceCell, double clientZ) {
        return VirtualPosition.clientToLocalZ(sourceCell, origin(player), clientZ);
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
