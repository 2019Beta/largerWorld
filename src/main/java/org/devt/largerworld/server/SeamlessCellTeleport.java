package org.devt.largerworld.server;

import net.minecraft.entity.EntityPosition;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
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
        player.networkHandler.requestTeleport(EntityPosition.fromTeleportTarget(target), target.relatives());
        player.networkHandler.syncWithPlayerPosition();
        to.onDimensionChanged(player);
        target.postTeleportTransition().onTransition(player);
        return player;
    }
}
