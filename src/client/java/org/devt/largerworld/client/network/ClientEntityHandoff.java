package org.devt.largerworld.client.network;

import org.devt.largerworld.Largerworld;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Client-thread state for preserving a ridden entity across a cell handoff. */
public final class ClientEntityHandoff {
    private static final int LATE_DESTROY_GRACE_TICKS = 20;
    private static final Map<Integer, State> ACTIVE = new HashMap<>();

    private ClientEntityHandoff() {
    }

    public static void update(int entityId, boolean begin) {
        if (begin) {
            ACTIVE.put(entityId, new State());
        } else {
            State state = ACTIVE.get(entityId);
            if (state != null) {
                state.ended = true;
                state.remainingTicks = LATE_DESTROY_GRACE_TICKS;
                if (state.destroySeen) {
                    ACTIVE.remove(entityId);
                }
            }
        }
        Largerworld.LOGGER.info(
                "[cell-transition] CLIENT_HANDOFF_{} entityId={}",
                begin ? "BEGIN" : "END", entityId);
    }

    public static boolean isActive(int entityId) {
        State state = ACTIVE.get(entityId);
        return state != null && !state.ended;
    }

    /** Returns true for the one source-world destroy that belongs to this handoff. */
    public static boolean preserveDestroy(int entityId) {
        State state = ACTIVE.get(entityId);
        if (state == null) {
            return false;
        }
        state.destroySeen = true;
        if (state.ended) {
            ACTIVE.remove(entityId);
        }
        return true;
    }

    public static void tick() {
        Iterator<Map.Entry<Integer, State>> iterator = ACTIVE.entrySet().iterator();
        while (iterator.hasNext()) {
            State state = iterator.next().getValue();
            if (state.ended && --state.remainingTicks <= 0) {
                iterator.remove();
            }
        }
    }

    private static final class State {
        private boolean ended;
        private boolean destroySeen;
        private int remainingTicks;
    }
}
