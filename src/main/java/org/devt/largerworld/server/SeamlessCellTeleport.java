package org.devt.largerworld.server;

import net.minecraft.entity.EntityPosition;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.TeleportTarget;
import org.devt.largerworld.mixin.EntityAccessor;
import org.devt.largerworld.mixin.TeleportTargetAccessor;

import java.util.function.Supplier;

/** Performs a cell-to-cell player move without sending a dimension respawn. */
public final class SeamlessCellTeleport {
    private static final ThreadLocal<HandoffMode> HANDOFF_MODE =
            ThreadLocal.withInitial(() -> HandoffMode.NONE);

    private SeamlessCellTeleport() {
    }

    /** Runs a cross-cell teleport and exposes its packet semantics to mixins. */
    public static <T> T withCellHandoff(boolean continuousMovement, Supplier<T> action) {
        HandoffMode previous = HANDOFF_MODE.get();
        HANDOFF_MODE.set(continuousMovement ? HandoffMode.CONTINUOUS : HandoffMode.TELEPORT);
        try {
            return action.get();
        } finally {
            if (previous == HandoffMode.NONE) {
                HANDOFF_MODE.remove();
            } else {
                HANDOFF_MODE.set(previous);
            }
        }
    }

    public static boolean isCellHandoff() {
        return HANDOFF_MODE.get() != HandoffMode.NONE;
    }

    public static boolean isContinuousMovement() {
        return HANDOFF_MODE.get() == HandoffMode.CONTINUOUS;
    }

    public static ServerPlayerEntity teleport(ServerPlayerEntity player, TeleportTarget target) {
        ServerWorld from = player.getEntityWorld();
        ServerWorld to = target.world();
        if (!((TeleportTargetAccessor) (Object) target).largerworld$isAsPassenger()) {
            player.dismountVehicle();
        }

        // The caller enters the target source context before teleportTo so the
        // eventual destination packets are mapped correctly. Removal happens
        // first, however, and its chunk/entity unload packets still describe
        // the old world. Explicitly restore that source or the client unloads
        // the already-preloaded destination view at the same mapped positions.
        CellPacketRouting.withSource(from, () ->
                from.removePlayer(player, net.minecraft.entity.Entity.RemovalReason.CHANGED_DIMENSION));
        ((EntityAccessor) player).largerworld$unsetRemoved();
        player.setServerWorld(to);

        EntityPosition targetPosition = EntityPosition.fromTeleportTarget(target);
        CellPacketRouting.withSource(to, () -> {
            if (isContinuousMovement()) {
                // The movement packet that crossed the seam already placed the
                // client at this exact stitched-world coordinate. Sending an
                // equivalent PlayerPositionLook packet would reset interpolation.
                // Apply a relative zero velocity delta on the server so both the
                // server player and its locally controlled client retain momentum.
                EntityPosition velocityPreservingPosition = new EntityPosition(
                        targetPosition.position(),
                        Vec3d.ZERO,
                        targetPosition.yaw(),
                        targetPosition.pitch());
                var positionFlags = PositionFlag.combine(target.relatives(), PositionFlag.DELTA);
                player.setPosition(velocityPreservingPosition, positionFlags);
            } else {
                player.networkHandler.requestTeleport(targetPosition, target.relatives());
            }
            player.networkHandler.syncWithPlayerPosition();
            to.onDimensionChanged(player);
            target.postTeleportTransition().onTransition(player);
        });
        return player;
    }

    private enum HandoffMode {
        NONE,
        TELEPORT,
        CONTINUOUS
    }
}
