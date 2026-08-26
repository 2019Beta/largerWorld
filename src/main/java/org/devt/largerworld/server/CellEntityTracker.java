package org.devt.largerworld.server;

import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;

/** Duck interface mixed into ServerChunkLoadingManager.EntityTracker. */
public interface CellEntityTracker {
    Entity largerworld$getEntity();

    void largerworld$startTracking(ServerPlayerEntity player);

    void largerworld$stopTracking(ServerPlayerEntity player);
}
