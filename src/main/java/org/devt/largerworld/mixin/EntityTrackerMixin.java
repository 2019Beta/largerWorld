package org.devt.largerworld.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.server.network.EntityTrackerEntry;
import net.minecraft.server.network.PlayerAssociatedNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.devt.largerworld.server.CellEntityTracker;
import org.devt.largerworld.server.CellViewTracker;
import org.devt.largerworld.Largerworld;
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
    /*
     * Do not rely solely on Mixin copying these field initializers into every
     * EntityTracker constructor. A constructor path used while a player joins
     * can therefore leave the injected field null before stopTracking is
     * called. Keep the initializer for the normal path and lazily repair the
     * field at every access.
     */
    @Unique private Set<PlayerAssociatedNetworkHandler> largerworld$shadowListeners =
            Collections.newSetFromMap(new IdentityHashMap<>());
    @Unique private Set<PlayerAssociatedNetworkHandler> largerworld$handoffListeners =
            Collections.newSetFromMap(new IdentityHashMap<>());

    @Unique
    private Set<PlayerAssociatedNetworkHandler> largerworld$getShadowListeners() {
        if (largerworld$shadowListeners == null) {
            largerworld$shadowListeners = Collections.newSetFromMap(new IdentityHashMap<>());
        }
        return largerworld$shadowListeners;
    }

    @Unique
    private Set<PlayerAssociatedNetworkHandler> largerworld$getHandoffListeners() {
        if (largerworld$handoffListeners == null) {
            largerworld$handoffListeners = Collections.newSetFromMap(new IdentityHashMap<>());
        }
        return largerworld$handoffListeners;
    }

    @Override
    public Entity largerworld$getEntity() {
        return entity;
    }

    @Override
    public void largerworld$beginHandoffTracking() {
        largerworld$getHandoffListeners().addAll(listeners);
    }

    @Override
    public void largerworld$abortHandoffTracking() {
        largerworld$getHandoffListeners().clear();
    }

    @Override
    public void largerworld$startShadowTracking(ServerPlayerEntity player) {
        largerworld$getShadowListeners().add(player.networkHandler);
        if (listeners.add(player.networkHandler)) {
            entry.startTracking(player);
        }
    }

    @Override
    public void largerworld$stopShadowTracking(ServerPlayerEntity player, boolean handedToVanilla) {
        largerworld$getShadowListeners().remove(player.networkHandler);
        if (largerworld$getHandoffListeners().remove(player.networkHandler)) {
            // CellViewTracker can retire a shadow watch directly without
            // entering EntityTracker.stopTracking(player). Preserve the same
            // silent handoff semantics on that path as well.
            listeners.remove(player.networkHandler);
            return;
        }
        if (handedToVanilla) {
            // A tracker that is itself becoming vanilla-owned must retain the
            // listener. A replaced source-world tracker is orphaned, however;
            // detach its listener silently so it cannot emit a late destroy.
            if (entity.getEntityWorld() != player.getEntityWorld()) {
                listeners.remove(player.networkHandler);
            }
        } else if (listeners.remove(player.networkHandler)) {
            entry.stopTracking(player);
        }
    }

    @Override
    public void largerworld$refreshTracking(ServerPlayerEntity player) {
        updateTrackedStatus(player);
    }

    @Inject(
            method = "stopTracking(Lnet/minecraft/server/network/ServerPlayerEntity;)V",
            at = @At("HEAD"),
            cancellable = true)
    private void largerworld$keepShadowListener(ServerPlayerEntity player, CallbackInfo ci) {
        if (largerworld$getHandoffListeners().remove(player.networkHandler)) {
            // The source tracker is being retired during an identity handoff.
            // Detach this listener without calling EntityTrackerEntry.stopTracking,
            // which would send the destroy packet that removes the retained
            // client vehicle.
            listeners.remove(player.networkHandler);
            ci.cancel();
            return;
        }
        boolean crossingControlledVehicle =
                CellViewTracker.shouldHoldCrossingControlledVehicle(player, entity);
        if (largerworld$getShadowListeners().contains(player.networkHandler)
                || CellViewTracker.shouldHoldCurrentCellEntity(player, entity)
                || crossingControlledVehicle) {
            if (crossingControlledVehicle) {
                Largerworld.LOGGER.info(
                        "[cell-handoff-server] PRE_BEGIN_HOLD type={} id={} uuid={} player={}",
                        entity.getType(), entity.getId(), entity.getUuid(), player.getUuid());
            }
            ci.cancel();
        }
    }

    @Inject(method = "stopTracking()V", at = @At("HEAD"))
    private void largerworld$silenceHandoffListenersOnStopAll(CallbackInfo ci) {
        Set<PlayerAssociatedNetworkHandler> handoffListeners = largerworld$getHandoffListeners();
        if (handoffListeners.isEmpty()) {
            return;
        }
        Largerworld.LOGGER.info(
                "[cell-handoff-server] STOP_ALL id={} uuid={} protectedListeners={}",
                entity.getId(), entity.getUuid(), handoffListeners.size());
        for (PlayerAssociatedNetworkHandler listener :
                Set.copyOf(handoffListeners)) {
            listeners.remove(listener);
        }
        handoffListeners.clear();
    }
}
