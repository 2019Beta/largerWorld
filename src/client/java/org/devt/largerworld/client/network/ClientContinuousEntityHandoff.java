package org.devt.largerworld.client.network;

import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.util.math.Vec3d;
import org.devt.largerworld.Largerworld;
import org.devt.largerworld.coordinate.CellPos;
import org.devt.largerworld.network.ContinuousEntityHandoffPayload;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Retains a continuously moving client entity and applies the target spawn to
 * that same Java object. This preserves subtype interpolation/controller state
 * without discarding the destination tracker's authoritative spawn contents.
 */
public final class ClientContinuousEntityHandoff {
    private static final long TIMEOUT_NANOS = 5_000_000_000L;
    private static final Map<Integer, Pending> PENDING = new ConcurrentHashMap<>();

    private ClientContinuousEntityHandoff() {
    }

    public static void accept(ContinuousEntityHandoffPayload payload) {
        if (payload.phase() == ContinuousEntityHandoffPayload.Phase.ABORT) {
            Pending pending = PENDING.get(payload.entityId());
            boolean removed = pending != null
                    && pending.matches(payload)
                    && PENDING.remove(payload.entityId(), pending);
            Largerworld.logEntityInfo(
                    "[continuous-handoff-client] MARKER phase=ABORT id={} uuid={} "
                            + "source={} target={} accepted={}",
                    payload.entityId(), payload.entityUuid(), payload.sourceCell(),
                    payload.targetCell(), removed);
            return;
        }

        long now = System.nanoTime();
        long expiresAtNanos = now + TIMEOUT_NANOS;
        Pending pending = PENDING.compute(payload.entityId(), (ignored, existing) -> {
            if (existing != null
                    && existing.expiresAtNanos - now >= 0L
                    && existing.matches(payload)) {
                return existing;
            }
            return new Pending(
                    payload.entityUuid(), payload.sourceCell(),
                    payload.targetCell(), expiresAtNanos);
        });
        Largerworld.logEntityInfo(
                "[continuous-handoff-client] MARKER phase=BEGIN id={} uuid={} "
                        + "source={} target={} state={}",
                payload.entityId(), payload.entityUuid(), payload.sourceCell(),
                payload.targetCell(), pending.targetSpawnConsumed ? "TARGET_CONSUMED" : "BEGIN");
    }

    /** Suppresses only the old source tracker's removal. */
    public static boolean shouldIgnoreDestroy(int entityId, Entity entity) {
        Pending pending = validPending(entityId, entity);
        return pending != null
                && pending.sourceCell.equals(ClientCellPacketContext.sourceCell());
    }

    /**
     * Applies a destination spawn to the retained object and returns whether
     * vanilla entity creation must be cancelled.
     */
    public static boolean consumeTargetSpawn(
            EntitySpawnS2CPacket packet, Entity existing) {
        if (existing == null
                || !existing.getUuid().equals(packet.getUuid())
                || existing.getType() != packet.getEntityType()) {
            return false;
        }
        Pending pending = validPending(packet.getEntityId(), existing);
        if (pending == null
                || !pending.targetCell.equals(ClientCellPacketContext.sourceCell())) {
            return false;
        }
        if (!pending.claimTargetSpawn()) {
            Largerworld.logEntityInfo(
                    "[continuous-handoff-client] SPAWN phase=DUPLICATE_DROP type={} "
                            + "id={} uuid={} source={} target={}",
                    existing.getType(), existing.getId(), existing.getUuid(),
                    pending.sourceCell, pending.targetCell);
            return true;
        }

        Vec3d retainedPosition = new Vec3d(
                existing.getX(), existing.getY(), existing.getZ());
        double retainedLastX = existing.lastX;
        double retainedLastY = existing.lastY;
        double retainedLastZ = existing.lastZ;
        double retainedLastRenderX = existing.lastRenderX;
        double retainedLastRenderY = existing.lastRenderY;
        double retainedLastRenderZ = existing.lastRenderZ;
        float retainedYaw = existing.getYaw();
        float retainedPitch = existing.getPitch();
        float retainedLastYaw = existing.lastYaw;
        float retainedLastPitch = existing.lastPitch;

        // Let the retained subtype consume all destination-specific spawn data.
        // The explicit velocity assignment also protects subclasses that do not
        // delegate their onSpawnPacket implementation to Entity.
        existing.onSpawnPacket(packet);
        existing.setVelocity(packet.getVelocity());

        Vec3d authoritativePosition = new Vec3d(
                existing.getX(), existing.getY(), existing.getZ());
        Vec3d reconciliationGap = authoritativePosition.subtract(retainedPosition);

        // The destination spawn is normally one or more server ticks ahead of
        // the retained client trajectory. Applying it as the current position
        // advances projectiles by a full tick and makes minecarts jump roughly
        // two interpolation samples. Keep the complete client trajectory where
        // it is. Entity#onSpawnPacket already installed the authoritative
        // tracked-position baseline, UUID, subtype spawn data and velocity.
        existing.setPosition(retainedPosition);
        existing.setYaw(retainedYaw);
        existing.setPitch(retainedPitch);
        existing.lastX = retainedLastX;
        existing.lastY = retainedLastY;
        existing.lastZ = retainedLastZ;
        existing.lastRenderX = retainedLastRenderX;
        existing.lastRenderY = retainedLastRenderY;
        existing.lastRenderZ = retainedLastRenderZ;
        existing.lastYaw = retainedLastYaw;
        existing.lastPitch = retainedLastPitch;

        // Do not clear or retarget an existing PositionInterpolator here.
        // Minecarts already have a source-tracker interpolation segment in
        // flight, and the coordinates of that segment are in the same stitched
        // client space. Restarting it toward the spawn snapshot stretches a
        // roughly one-to-two tick gap over vanilla's three-tick duration and is
        // perceived as a short slowdown at the seam. The next target movement
        // packet will replace the target through vanilla's normal path.

        Largerworld.logEntityInfo(
                "[continuous-handoff-client] SPAWN phase=CONSUME type={} id={} uuid={} "
                        + "source={} target={} retainedPos={} authoritativePos={} gap={} velocity={} "
                        + "lastRender=({}, {}, {})",
                existing.getType(), existing.getId(), existing.getUuid(),
                pending.sourceCell, pending.targetCell, retainedPosition,
                authoritativePosition, reconciliationGap, existing.getVelocity(), existing.lastRenderX,
                existing.lastRenderY, existing.lastRenderZ);
        return true;
    }

    /** Drops a queued source update only after the target spawn was consumed. */
    public static boolean shouldIgnoreTrackerUpdate(Entity entity) {
        if (entity == null) {
            return false;
        }
        Pending pending = validPending(entity.getId(), entity);
        return pending != null
                && pending.targetSpawnConsumed
                && pending.sourceCell.equals(ClientCellPacketContext.sourceCell());
    }

    public static boolean isTargetTrackerUpdate(Entity entity) {
        if (entity == null) {
            return false;
        }
        Pending pending = validPending(entity.getId(), entity);
        return pending != null
                && pending.targetSpawnConsumed
                && pending.targetCell.equals(ClientCellPacketContext.sourceCell());
    }

    public static String debugState(int entityId, Entity entity) {
        Pending pending = validPending(entityId, entity);
        if (pending == null) {
            return "NONE";
        }
        return pending.targetSpawnConsumed ? "TARGET_CONSUMED" : "BEGIN";
    }

    /** Removes an orphaned retained entity if its target spawn never arrives. */
    public static void tick(ClientWorld world) {
        if (world == null) {
            PENDING.clear();
            return;
        }
        long now = System.nanoTime();
        for (Map.Entry<Integer, Pending> entry : PENDING.entrySet()) {
            Pending pending = entry.getValue();
            if (pending.expiresAtNanos - now >= 0L
                    || !PENDING.remove(entry.getKey(), pending)) {
                continue;
            }
            Entity entity = world.getEntityById(entry.getKey());
            if (!pending.targetSpawnConsumed
                    && entity != null
                    && pending.uuid.equals(entity.getUuid())) {
                Largerworld.LOGGER.warn(
                        "[continuous-handoff-client] TIMEOUT_REMOVE type={} id={} uuid={}",
                        entity.getType(), entity.getId(), entity.getUuid());
                world.removeEntity(entity.getId(), Entity.RemovalReason.DISCARDED);
            }
        }
    }

    private static Pending validPending(int entityId, Entity entity) {
        Pending pending = PENDING.get(entityId);
        if (pending == null || pending.expiresAtNanos - System.nanoTime() < 0L) {
            return null;
        }
        return entity == null || pending.uuid.equals(entity.getUuid()) ? pending : null;
    }

    private static final class Pending {
        private final UUID uuid;
        private final CellPos sourceCell;
        private final CellPos targetCell;
        private final long expiresAtNanos;
        private volatile boolean targetSpawnConsumed;

        private Pending(
                UUID uuid,
                CellPos sourceCell,
                CellPos targetCell,
                long expiresAtNanos) {
            this.uuid = uuid;
            this.sourceCell = sourceCell;
            this.targetCell = targetCell;
            this.expiresAtNanos = expiresAtNanos;
        }

        private synchronized boolean claimTargetSpawn() {
            if (targetSpawnConsumed) {
                return false;
            }
            targetSpawnConsumed = true;
            return true;
        }

        private boolean matches(ContinuousEntityHandoffPayload payload) {
            return uuid.equals(payload.entityUuid())
                    && sourceCell.equals(payload.sourceCell())
                    && targetCell.equals(payload.targetCell());
        }
    }
}
