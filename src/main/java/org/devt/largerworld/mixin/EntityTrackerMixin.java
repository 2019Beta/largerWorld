package org.devt.largerworld.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.server.network.EntityTrackerEntry;
import net.minecraft.server.network.PlayerAssociatedNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.devt.largerworld.server.CellEntityTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Set;

@Mixin(targets = "net.minecraft.server.world.ServerChunkLoadingManager$EntityTracker")
public abstract class EntityTrackerMixin implements CellEntityTracker {
    @Shadow private Entity entity;
    @Shadow private EntityTrackerEntry entry;
    @Shadow private Set<PlayerAssociatedNetworkHandler> listeners;

    @Override
    public Entity largerworld$getEntity() {
        return entity;
    }

    @Override
    public void largerworld$startTracking(ServerPlayerEntity player) {
        if (listeners.add(player.networkHandler)) {
            entry.startTracking(player);
        }
    }

    @Override
    public void largerworld$stopTracking(ServerPlayerEntity player) {
        if (listeners.remove(player.networkHandler)) {
            entry.stopTracking(player);
        }
    }
}
