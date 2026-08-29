package org.devt.largerworld.server;

import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;

/** Duck interface mixed into ServerChunkLoadingManager.EntityTracker. */
public interface CellEntityTracker {
    Entity largerworld$getEntity();

    /** Marks current listeners whose client entity must survive tracker replacement. */
    void largerworld$beginHandoffTracking();

    /** Clears identity protection when teleportation fails before replacement. */
    void largerworld$abortHandoffTracking();

    void largerworld$startShadowTracking(ServerPlayerEntity player);

    void largerworld$stopShadowTracking(ServerPlayerEntity player, boolean handedToVanilla);

    void largerworld$refreshTracking(ServerPlayerEntity player);
}
