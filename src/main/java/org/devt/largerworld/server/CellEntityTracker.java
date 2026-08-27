package org.devt.largerworld.server;

import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;

/** Duck interface mixed into ServerChunkLoadingManager.EntityTracker. */
public interface CellEntityTracker {
    Entity largerworld$getEntity();

    void largerworld$startShadowTracking(ServerPlayerEntity player);

    void largerworld$stopShadowTracking(ServerPlayerEntity player, boolean handedToVanilla);

    void largerworld$refreshTracking(ServerPlayerEntity player);
}
