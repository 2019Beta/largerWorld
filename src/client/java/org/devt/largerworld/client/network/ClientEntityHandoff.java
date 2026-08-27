package org.devt.largerworld.client.network;

import net.minecraft.entity.Entity;
import org.devt.largerworld.network.EntityHandoffPayload;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Keeps client-owned entity state alive while the server changes cell worlds. */
public final class ClientEntityHandoff {
    private static final long TIMEOUT_MILLIS = 5_000L;
    // Payload reception and vanilla packet application are normally ordered on
    // the render thread, but an integrated connection may enter the handler
    // from its networking thread first. Make the handoff marker safely visible
    // to both paths.
    private static final Map<Integer, Pending> PENDING = new ConcurrentHashMap<>();

    private ClientEntityHandoff() {
    }

    public static void begin(EntityHandoffPayload payload) {
        prune();
        PENDING.put(payload.entityId(),
                new Pending(payload.entityUuid(), System.currentTimeMillis() + TIMEOUT_MILLIS));
    }

    public static boolean shouldKeep(Entity entity) {
        if (entity == null) {
            return false;
        }
        Pending pending = PENDING.get(entity.getId());
        if (pending == null) {
            return false;
        }
        if (pending.expiresAtMillis() < System.currentTimeMillis()) {
            PENDING.remove(entity.getId());
            return false;
        }
        return pending.uuid().equals(entity.getUuid());
    }

    private static void prune() {
        long now = System.currentTimeMillis();
        PENDING.values().removeIf(pending -> pending.expiresAtMillis() < now);
    }

    private record Pending(UUID uuid, long expiresAtMillis) {
    }
}
