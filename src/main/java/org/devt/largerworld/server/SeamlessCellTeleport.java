package org.devt.largerworld.server;

import net.minecraft.entity.EntityPosition;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.TeleportTarget;
import org.devt.largerworld.mixin.EntityAccessor;
import org.devt.largerworld.mixin.TeleportTargetAccessor;
import org.devt.largerworld.world.CellWorldKey;

import java.util.function.Supplier;

/** Performs a cell-to-cell player move without sending a dimension respawn. */
public final class SeamlessCellTeleport {
    private static final ThreadLocal<Boolean> CONTINUOUS_MOVEMENT =
            ThreadLocal.withInitial(() -> false);

    private SeamlessCellTeleport() {
    }

    /** Runs a boundary crossing whose final client-space position is unchanged. */
    public static <T> T withContinuousMovement(Supplier<T> action) {
        boolean previous = CONTINUOUS_MOVEMENT.get();
        CONTINUOUS_MOVEMENT.set(true);
        try {
            return action.get();
        } finally {
            CONTINUOUS_MOVEMENT.set(previous);
        }
    }

    public static boolean isContinuousMovement() {
        return CONTINUOUS_MOVEMENT.get();
    }

    public static ServerPlayerEntity teleport(ServerPlayerEntity player, TeleportTarget target) {
        ServerWorld from = player.getEntityWorld();
        ServerWorld to = target.world();
        if (!((TeleportTargetAccessor) (Object) target).largerworld$isAsPassenger()) {
            player.dismountVehicle();
        }

        CellViewTracker.prepareTransition(
                from.getServer(),
                player,
                CellWorldKey.cell(to.getRegistryKey()),
                target.position().x,
                target.position().z);

        // The caller enters the target source context before teleportTo so the
        // eventual destination packets are mapped correctly. Removal happens
        // first, however, and its chunk/entity unload packets still describe
        // the old world. Explicitly restore that source or the client unloads
        // the already-preloaded destination view at the same mapped positions.
        CellPacketRouting.withSource(from, () ->
                from.removePlayer(player, net.minecraft.entity.Entity.RemovalReason.CHANGED_DIMENSION));
        ((EntityAccessor) player).largerworld$unsetRemoved();
        player.setServerWorld(to);

        // The server does not continuously mirror a locally controlled player's
        // horizontal velocity. Sending target.velocity() as an absolute value can
        // therefore replace the client's current momentum with a stale value (very
        // often zero). A cell transition is only a translation, so encode velocity
        // as a relative zero delta: both sides retain their own current value.
        EntityPosition targetPosition = EntityPosition.fromTeleportTarget(target);
        EntityPosition velocityPreservingPosition = new EntityPosition(
                targetPosition.position(),
                Vec3d.ZERO,
                targetPosition.yaw(),
                targetPosition.pitch());
        var positionFlags = PositionFlag.combine(target.relatives(), PositionFlag.DELTA);
        CellPacketRouting.withSource(to, () -> {
            if (CONTINUOUS_MOVEMENT.get()) {
                // The movement packet that crossed the seam already placed the
                // client at this exact stitched-world coordinate. Sending an
                // equivalent PlayerPositionLook packet still resets client-side
                // interpolation and creates a visible one-frame blink.
                player.setPosition(velocityPreservingPosition, positionFlags);
            } else {
                player.networkHandler.requestTeleport(velocityPreservingPosition, positionFlags);
            }
            player.networkHandler.syncWithPlayerPosition();
            to.onDimensionChanged(player);
            target.postTeleportTransition().onTransition(player);
        });
        return player;
    }
}
