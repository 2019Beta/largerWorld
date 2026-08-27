package org.devt.largerworld.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.server.network.EntityTrackerEntry;
import net.minecraft.server.network.PlayerAssociatedNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.devt.largerworld.server.CellEntityTracker;
import org.devt.largerworld.server.CellViewTracker;
import org.devt.largerworld.server.SeamlessCellTeleport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

@Mixin(targets = "net.minecraft.server.world.ServerChunkLoadingManager$EntityTracker")
public abstract class EntityTrackerMixin implements CellEntityTracker {
    @Shadow private Entity entity;
    @Shadow private EntityTrackerEntry entry;
    @Shadow private Set<PlayerAssociatedNetworkHandler> listeners;
    @Shadow public abstract void updateTrackedStatus(ServerPlayerEntity player);
    @Unique private final Set<PlayerAssociatedNetworkHandler> largerworld$shadowListeners =
            Collections.newSetFromMap(new IdentityHashMap<>());

    @Override
    public Entity largerworld$getEntity() {
        return entity;
    }

    @Override
    public void largerworld$startShadowTracking(ServerPlayerEntity player) {
        largerworld$shadowListeners.add(player.networkHandler);
        if (listeners.add(player.networkHandler)) {
            entry.startTracking(player);
        }
    }

    @Override
    public void largerworld$stopShadowTracking(ServerPlayerEntity player, boolean handedToVanilla) {
        largerworld$shadowListeners.remove(player.networkHandler);
        if (!handedToVanilla && listeners.remove(player.networkHandler)) {
            entry.stopTracking(player);
        }
    }

    @Override
    public void largerworld$refreshTracking(ServerPlayerEntity player) {
        updateTrackedStatus(player);
    }

    /**
     * Vanilla drops every listener while removing a player from the old world.
     * Keep listeners that the stitched shadow view claimed before that removal;
     * otherwise the client observes a destroy followed by a spawn one tick later.
     */
    @Inject(method = "stopTracking()V", at = @At("HEAD"), cancellable = true)
    private void largerworld$keepAllListenersDuringMigration(CallbackInfo ci) {
        if (SeamlessCellTeleport.isContinuousMovement()) {
            ci.cancel();
        }
    }

    @Inject(
            method = "stopTracking(Lnet/minecraft/server/network/ServerPlayerEntity;)V",
            at = @At("HEAD"),
            cancellable = true)
    private void largerworld$keepShadowListener(ServerPlayerEntity player, CallbackInfo ci) {
        if (SeamlessCellTeleport.isContinuousMovement()
                || largerworld$shadowListeners.contains(player.networkHandler)
                || CellViewTracker.shouldHoldCurrentCellEntity(player, entity)) {
            ci.cancel();
        }
    }
}
