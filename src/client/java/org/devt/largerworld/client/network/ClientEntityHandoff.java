package org.devt.largerworld.client.network;

import net.minecraft.entity.Entity;
import org.devt.largerworld.Largerworld;
import org.devt.largerworld.coordinate.CellPos;
import org.devt.largerworld.network.EntityHandoffPayload;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Short-lived identity bridge between source and destination entity trackers. */
public final class ClientEntityHandoff {
    // CellViewTracker keeps its ownership-overlap grace period for 40 server
    // ticks. Keep the client token beyond that window so a delayed source
    // tracker removal cannot land after COMMIT and delete the retained entity.
    private static final long TIMEOUT_NANOS = 5_000_000_000L;
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
            long expiresAtNanos = System.nanoTime() + TIMEOUT_NANOS;
            Pending pending = PENDING.compute(payload.entityId(), (ignored, existing) -> {
                if (existing != null
                        && existing.expiresAtNanos - System.nanoTime() >= 0L
                        && existing.matches(payload)) {
                    return existing;
                }
                return new Pending(
                        payload.entityUuid(),
                        payload.sourceCell(),
                        payload.targetCell(),
                        expiresAtNanos);
            });
            Largerworld.LOGGER.info(
                    "[cell-handoff-client] MARKER phase=BEGIN id={} uuid={} source={} target={} state={}",
                    payload.entityId(), payload.entityUuid(), payload.sourceCell(),
                    payload.targetCell(), pending.committed ? "COMMITTED" : "BEGIN");
            return;
        }

        Pending pending = PENDING.get(payload.entityId());
        boolean accepted = pending != null && pending.matches(payload);
        if (accepted) {
            pending.committed = true;
        }
        Largerworld.LOGGER.info(
                "[cell-handoff-client] MARKER phase=COMMIT id={} uuid={} source={} target={} accepted={}",
                payload.entityId(), payload.entityUuid(), payload.sourceCell(),
                payload.targetCell(), accepted);
    }

    /** Ignores only the old tracker's removal, never a real target-cell removal. */
    public static boolean shouldIgnoreDestroy(int entityId, Entity entity) {
        Pending pending = validPending(entityId, entity);
        if (pending == null
                || !pending.sourceCell.equals(ClientCellPacketContext.sourceCell())) {
            return false;
        }
        // Do not retire the token here. More than one source-side listener can
        // converge after the target graph has already committed.
        return true;
    }

    /** Ignores only the replacement spawn produced by the destination tracker. */
    public static boolean shouldIgnoreSpawn(Entity entity) {
        Pending pending = validPending(entity);
        if (pending == null
                || !pending.targetCell.equals(ClientCellPacketContext.sourceCell())) {
            return false;
        }
        // A destination tracker may be refreshed during the same convergence
        // window. Every matching target spawn is a duplicate of the retained
        // client entity, not a new lifecycle.
        pending.targetTrackerSeen = true;
        return true;
    }

    /** Drops stale source-tracker state once destination ownership is visible. */
    public static boolean shouldIgnoreTrackerUpdate(Entity entity) {
        Pending pending = validPending(entity);
        if (pending == null) {
            return false;
        }
        CellPos packetSource = ClientCellPacketContext.sourceCell();
        if (pending.targetCell.equals(packetSource)) {
            pending.targetTrackerSeen = true;
            return false;
        }
        return pending.sourceCell.equals(packetSource)
                && (pending.committed || pending.targetTrackerSeen);
    }

    /** Human-readable state used by the bounded passenger handoff diagnostic. */
    public static String debugState(int entityId, Entity entity) {
        Pending pending = validPending(entityId, entity);
        if (pending == null) {
            return "NONE";
        }
        return pending.committed ? "COMMITTED" : "BEGIN";
    }

    /** Suppresses temporary snapshots until the server commits the rebuilt graph. */
    public static boolean shouldIgnorePassengerUpdate(
            int entityId, Entity entity, int[] passengerIds) {
        Pending pending = validPending(entityId, entity);
        if (pending == null) {
            return false;
        }

        // The source tag is not a reliable phase marker inside vanilla's
        // cross-world passenger traversal: temporary detach packets can inherit
        // the outer target-world context. Until the ordered COMMIT is applied,
        // every passenger snapshot is transitional and must be suppressed.
        if (!pending.committed) {
            return true;
        }

        CellPos packetSource = ClientCellPacketContext.sourceCell();
        if (pending.sourceCell.equals(packetSource)) {
            return true;
        }
        if (!pending.targetCell.equals(packetSource)) {
            return false;
        }
        if (entity == null) {
            // The final target snapshot will be deferred by the packet mixin
            // until the retained/replacement entity is available again.
            return false;
        }

        // COMMIT, rather than a replacement spawn, establishes target tracker
        // authority. Do not replay an unchanged final graph through vanilla:
        // its handler removes every passenger and calls startRiding again.
        // Besides needless object churn, a prior detach leak makes that path
        // show the "press Shift to dismount" onboarding message a second time.
        int[] currentPassengerIds = entity.getPassengerList().stream()
                .mapToInt(Entity::getId)
                .toArray();
        pending.targetTrackerSeen = true;
        return Arrays.equals(currentPassengerIds, passengerIds);
    }

    private static Pending validPending(Entity entity) {
        if (entity == null) {
            return null;
        }
        return validPending(entity.getId(), entity);
    }

    private static Pending validPending(int entityId, Entity entity) {
        Pending pending = PENDING.get(entityId);
        if (pending == null) {
            return null;
        }
        if (pending.expiresAtNanos - System.nanoTime() < 0L) {
            PENDING.remove(entityId, pending);
            return null;
        }
        return entity == null || pending.uuid.equals(entity.getUuid()) ? pending : null;
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
        private volatile boolean committed;
        private volatile boolean targetTrackerSeen;

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
