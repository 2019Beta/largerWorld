package org.devt.largerworld.server;

import net.minecraft.entity.EntityPosition;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.TeleportTarget;
import org.devt.largerworld.mixin.EntityAccessor;
import org.devt.largerworld.mixin.TeleportTargetAccessor;

/** Performs a cell-to-cell player move without sending a dimension respawn. */
public final class SeamlessCellTeleport {
    private SeamlessCellTeleport() {
    }

    public static ServerPlayerEntity teleport(ServerPlayerEntity player, TeleportTarget target) {
        ServerWorld from = player.getEntityWorld();
        ServerWorld to = target.world();
        if (!((TeleportTargetAccessor) (Object) target).largerworld$isAsPassenger()) {
            player.dismountVehicle();
        }

        from.removePlayer(player, net.minecraft.entity.Entity.RemovalReason.CHANGED_DIMENSION);
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
        player.networkHandler.requestTeleport(
                velocityPreservingPosition,
                PositionFlag.combine(target.relatives(), PositionFlag.DELTA));
        player.networkHandler.syncWithPlayerPosition();
        to.onDimensionChanged(player);
        target.postTeleportTransition().onTransition(player);
        return player;
    }
}
