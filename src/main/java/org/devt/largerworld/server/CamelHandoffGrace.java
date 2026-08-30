package org.devt.largerworld.server;

import net.minecraft.entity.passive.CamelEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Prevents a rebuilt camel brain from immediately re-rolling its pose task. */
public final class CamelHandoffGrace {
    private static final long POSE_GRACE_TICKS = 40L;
    private static final Map<UUID, Long> POSE_GRACE_UNTIL = new HashMap<>();

    private CamelHandoffGrace() {
    }

    public static void clearServerState() {
        POSE_GRACE_UNTIL.clear();
    }

    public static void mark(CamelEntity camel) {
        POSE_GRACE_UNTIL.put(
                camel.getUuid(), camel.getEntityWorld().getTime() + POSE_GRACE_TICKS);
    }

    public static boolean shouldSuppressPoseToggle(CamelEntity camel) {
        Long deadline = POSE_GRACE_UNTIL.get(camel.getUuid());
        if (deadline == null) {
            return false;
        }
        if (camel.getEntityWorld().getTime() < deadline) {
            return true;
        }
        POSE_GRACE_UNTIL.remove(camel.getUuid(), deadline);
        return false;
    }
}
