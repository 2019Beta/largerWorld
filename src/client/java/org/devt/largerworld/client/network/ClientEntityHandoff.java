package org.devt.largerworld.client.network;

import net.minecraft.entity.Entity;
import org.devt.largerworld.coordinate.CellPos;
import org.devt.largerworld.network.EntityHandoffPayload;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Short-lived identity bridge between source and destination entity trackers. */
public final class ClientEntityHandoff {
    private static final long TIMEOUT_NANOS = 2_000_000_000L;
    // Payload reception and vanilla packet application are normally ordered on
    // the render thread, but an integrated connection may enter the handler
    // from its networking thread first. Make the handoff marker safely visible
    // to both paths.
    private static final Map<Integer, Pending> PENDING = new ConcurrentHashMap<>();

    private ClientEntityHandoff() {
    }

    public static void accept(EntityHandoffPayload payload) {
        prune();
        if (payload.phase() == EntityHandoffPayload.Phase.BEGIN) {
            PENDING.put(payload.entityId(),
                    new Pending(
                            payload.entityUuid(),
                            payload.sourceCell(),
                            payload.targetCell(),
                            System.nanoTime() + TIMEOUT_NANOS));
            return;
        }

        PENDING.computeIfPresent(payload.entityId(), (ignored, pending) -> {
            if (pending.matches(payload)) {
                pending.committed = true;
            }
            return pending;
        });
    }

    /** Ignores only the old tracker's removal, never a real target-cell removal. */
    public static boolean shouldIgnoreDestroy(Entity entity) {
        Pending pending = validPending(entity);
        if (pending == null
                || pending.sourceDestroySeen
                || !pending.sourceCell.equals(ClientCellPacketContext.sourceCell())) {
            return false;
        }
        pending.sourceDestroySeen = true;
        return true;
    }

    /** Ignores only the replacement spawn produced by the destination tracker. */
    public static boolean shouldIgnoreSpawn(Entity entity) {
        Pending pending = validPending(entity);
        if (pending == null
                || pending.targetSpawnSeen
                || !pending.targetCell.equals(ClientCellPacketContext.sourceCell())) {
            return false;
        }
        pending.targetSpawnSeen = true;
        return true;
    }

    /** Suppresses temporary snapshots until the server commits the rebuilt graph. */
    public static boolean shouldIgnorePassengerUpdate(Entity entity, int[] passengerIds) {
        Pending pending = validPending(entity);
        if (pending == null) {
            return false;
        }
        CellPos packetSource = ClientCellPacketContext.sourceCell();
        if (pending.sourceCell.equals(packetSource)) {
            return true;
        }
        if (!pending.targetCell.equals(packetSource)) {
            return false;
        }
        if (!pending.committed) {
            return true;
        }

        // COMMIT, rather than a replacement spawn, establishes target tracker
        // authority. Shadow-tracker takeover deliberately has no spawn packet.
        // Always apply the first committed snapshot, even when it differs from
        // the retained client graph, then retire the token immediately.
        PENDING.remove(entity.getId(), pending);
        return false;
    }

    private static Pending validPending(Entity entity) {
        if (entity == null) {
            return null;
        }
        Pending pending = PENDING.get(entity.getId());
        if (pending == null) {
            return null;
        }
        if (pending.expiresAtNanos - System.nanoTime() < 0L) {
            PENDING.remove(entity.getId(), pending);
            return null;
        }
        return pending.uuid.equals(entity.getUuid()) ? pending : null;
    }

    private static void prune() {
        long now = System.nanoTime();
        PENDING.values().removeIf(pending -> pending.expiresAtNanos - now < 0L);
    }

    private static final class Pending {
        private final UUID uuid;
        private final CellPos sourceCell;
        private final CellPos targetCell;
        private final long expiresAtNanos;
        private volatile boolean sourceDestroySeen;
        private volatile boolean targetSpawnSeen;
        private volatile boolean committed;

        private Pending(
                UUID uuid, CellPos sourceCell, CellPos targetCell, long expiresAtNanos) {
            this.uuid = uuid;
            this.sourceCell = sourceCell;
            this.targetCell = targetCell;
            this.expiresAtNanos = expiresAtNanos;
        }

        private boolean matches(EntityHandoffPayload payload) {
            return uuid.equals(payload.entityUuid())
                    && sourceCell.equals(payload.sourceCell())
                    && targetCell.equals(payload.targetCell());
        }
    }
}
