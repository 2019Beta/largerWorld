package org.devt.largerworld.mixin.client;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.passive.CamelEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.network.packet.s2c.play.EntitiesDestroyS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityPassengersSetS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityPositionS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityPositionSyncS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import org.devt.largerworld.Largerworld;
import org.devt.largerworld.client.network.ClientCellPacketContext;
import org.devt.largerworld.client.network.ClientContinuousEntityHandoff;
import org.devt.largerworld.client.network.ClientEntityHandoff;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Keeps a client entity continuous while its server tracker changes cells. */
@Mixin(ClientPlayNetworkHandler.class)
public abstract class EntitySpawnPacketHandlerMixin {
    @Unique
    private final Map<Integer, EntityPassengersSetS2CPacket>
            largerworld$pendingPassengers = new HashMap<>();

    @Inject(method = "onEntitySpawn", at = @At("HEAD"), cancellable = true)
    private void largerworld$ignoreDuplicateSpawn(EntitySpawnS2CPacket packet, CallbackInfo ci) {
        net.minecraft.client.MinecraftClient client =
                net.minecraft.client.MinecraftClient.getInstance();
        if (!client.isOnThread()) {
            return;
        }

        ClientWorld world = ((ClientPlayNetworkHandler) (Object) this).getWorld();
        Entity existing = world == null
                ? null : world.getEntityById(packet.getEntityId());
        String handoffState = ClientEntityHandoff.debugState(packet.getEntityId(), existing);
        String continuousState =
                ClientContinuousEntityHandoff.debugState(packet.getEntityId(), existing);
        boolean consumedContinuousSpawn =
                ClientContinuousEntityHandoff.consumeTargetSpawn(packet, existing);
        boolean matchingExisting = existing != null
                && existing.getUuid().equals(packet.getUuid())
                && ClientEntityHandoff.shouldIgnoreSpawn(existing);
        Largerworld.LOGGER.info(
                "[cell-handoff-client] SPAWN type={} id={} uuid={} source={} existingUuid={} "
                        + "velocity={} identityState={} continuousState={} decision={}",
                packet.getEntityType(), packet.getEntityId(), packet.getUuid(),
                ClientCellPacketContext.sourceCell(),
                existing == null ? null : existing.getUuid(), packet.getVelocity(),
                handoffState, continuousState,
                consumedContinuousSpawn ? "CONSUME"
                        : matchingExisting ? "DROP" : "APPLY");
        if (consumedContinuousSpawn || matchingExisting) {
            // This spawn describes the seam position at the instant the server
            // rebuilt the entity. A controlled vehicle ignores that duplicate;
            // other continuously moving entities have already consumed it into
            // the retained Java object above.
            ci.cancel();
            return;
        }
    }

    @Inject(method = "onEntitiesDestroy", at = @At("HEAD"), cancellable = true)
    private void largerworld$ignoreSourceDestroy(
            EntitiesDestroyS2CPacket packet, CallbackInfo ci) {
        net.minecraft.client.MinecraftClient client =
                net.minecraft.client.MinecraftClient.getInstance();
        if (!client.isOnThread()) {
            return;
        }

        ClientPlayNetworkHandler handler = (ClientPlayNetworkHandler) (Object) this;
        ClientWorld world = handler.getWorld();
        IntArrayList remaining = new IntArrayList(packet.getEntityIds().size());
        boolean filtered = false;
        for (int entityId : packet.getEntityIds()) {
            Entity entity = world == null ? null : world.getEntityById(entityId);
            String handoffState = ClientEntityHandoff.debugState(entityId, entity);
            String continuousState =
                    ClientContinuousEntityHandoff.debugState(entityId, entity);
            boolean ignoreIdentity =
                    ClientEntityHandoff.shouldIgnoreDestroy(entityId, entity);
            boolean ignoreContinuous =
                    ClientContinuousEntityHandoff.shouldIgnoreDestroy(entityId, entity);
            boolean ignore = ignoreIdentity || ignoreContinuous;
            Largerworld.LOGGER.info(
                    "[cell-handoff-client] DESTROY type={} id={} uuid={} source={} "
                            + "identityState={} continuousState={} decision={}",
                    entity == null ? null : entity.getType(), entityId,
                    entity == null ? null : entity.getUuid(),
                    ClientCellPacketContext.sourceCell(), handoffState, continuousState,
                    ignore ? "DROP" : "APPLY");
            if (ignore) {
                filtered = true;
            } else {
                remaining.add(entityId);
            }
        }
        if (!filtered) {
            return;
        }

        ci.cancel();
        if (!remaining.isEmpty()) {
            handler.onEntitiesDestroy(new EntitiesDestroyS2CPacket(remaining));
        }
    }

    @Inject(method = "onEntityPassengersSet", at = @At("HEAD"), cancellable = true)
    private void largerworld$deferPassengersUntilEntityExists(
            EntityPassengersSetS2CPacket packet, CallbackInfo ci) {
        // The first invocation can be on Netty's thread. Leave it to vanilla's
        // forceMainThread path; the scheduled invocation will enter here again.
        net.minecraft.client.MinecraftClient client =
                net.minecraft.client.MinecraftClient.getInstance();
        if (!client.isOnThread()) {
            return;
        }

        ClientWorld world = ((ClientPlayNetworkHandler) (Object) this).getWorld();
        Entity vehicle = world == null
                ? null : world.getEntityById(packet.getEntityId());
        String handoffState = ClientEntityHandoff.debugState(packet.getEntityId(), vehicle);
        int[] currentPassengerIds = vehicle == null
                ? new int[0]
                : vehicle.getPassengerList().stream().mapToInt(Entity::getId).toArray();
        ClientEntityHandoff.PassengerDecision passengerDecision =
                ClientEntityHandoff.passengerDecision(
                packet.getEntityId(), vehicle, packet.getPassengerIds());
        if (!"NONE".equals(handoffState)
                || ClientCellPacketContext.isApplyingCellPacket()) {
            Largerworld.LOGGER.info(
                    "[cell-handoff-client] PASSENGERS vehicle={} uuid={} source={} "
                            + "incoming={} current={} state={} decision={}",
                    packet.getEntityId(),
                    vehicle == null ? null : vehicle.getUuid(),
                    ClientCellPacketContext.sourceCell(),
                    Arrays.toString(packet.getPassengerIds()),
                    Arrays.toString(currentPassengerIds),
                    handoffState,
                    passengerDecision);
        }
        if (passengerDecision == ClientEntityHandoff.PassengerDecision.HOLD) {
            ClientEntityHandoff.deferPassengerUpdate(packet);
            ci.cancel();
            return;
        }
        if (passengerDecision == ClientEntityHandoff.PassengerDecision.DROP) {
            ci.cancel();
            return;
        }
        if (ClientCellPacketContext.isApplyingCellPacket()
                && (world == null || world.getEntityById(packet.getEntityId()) == null)) {
            largerworld$pendingPassengers.put(packet.getEntityId(), packet);
            ci.cancel();
        }
    }

    @Inject(method = "onEntitySpawn", at = @At("RETURN"))
    private void largerworld$applyDeferredPassengers(
            EntitySpawnS2CPacket packet, CallbackInfo ci) {
        EntityPassengersSetS2CPacket pending =
                largerworld$pendingPassengers.remove(packet.getEntityId());
        if (pending != null) {
            ((ClientPlayNetworkHandler) (Object) this)
                    .onEntityPassengersSet(pending);
        }
    }

    @Inject(method = "onEntity", at = @At("HEAD"), cancellable = true)
    private void largerworld$ignoreStaleRelativeMove(EntityS2CPacket packet, CallbackInfo ci) {
        ClientWorld world = largerworld$worldOnClientThread();
        Entity entity = world == null ? null : packet.getEntity(world);
        if (ClientEntityHandoff.shouldIgnoreTrackerUpdate(entity)
                || ClientContinuousEntityHandoff.shouldIgnoreTrackerUpdate(entity)) {
            ci.cancel();
        }
    }

    @Inject(method = "onEntityPosition", at = @At("HEAD"), cancellable = true)
    private void largerworld$ignoreStalePosition(
            EntityPositionS2CPacket packet, CallbackInfo ci) {
        if (largerworld$ignoreTrackerUpdate(packet.entityId())) {
            ci.cancel();
        }
    }

    @Inject(method = "onEntityPositionSync", at = @At("HEAD"), cancellable = true)
    private void largerworld$ignoreStalePositionSync(
            EntityPositionSyncS2CPacket packet, CallbackInfo ci) {
        if (largerworld$ignoreTrackerUpdate(packet.id())) {
            ci.cancel();
        }
    }

    @Inject(method = "onEntityVelocityUpdate", at = @At("HEAD"), cancellable = true)
    private void largerworld$ignoreStaleVelocity(
            EntityVelocityUpdateS2CPacket packet, CallbackInfo ci) {
        ClientWorld world = largerworld$worldOnClientThread();
        if (world == null) {
            return;
        }
        Entity entity = world.getEntityById(packet.getEntityId());
        String handoffState = ClientEntityHandoff.debugState(packet.getEntityId(), entity);
        String continuousState =
                ClientContinuousEntityHandoff.debugState(packet.getEntityId(), entity);
        boolean ignore = ClientEntityHandoff.shouldIgnoreTrackerUpdate(entity)
                || ClientContinuousEntityHandoff.shouldIgnoreTrackerUpdate(entity);
        if (entity == null
                || entity instanceof ProjectileEntity
                || entity.hasPassengers()
                || !"NONE".equals(handoffState)) {
            Largerworld.LOGGER.info(
                    "[cross-velocity-client] type={} id={} uuid={} source={} identityState={} "
                            + "continuousState={} "
                            + "before={} packet={} decision={}",
                    entity == null ? null : entity.getType(), packet.getEntityId(),
                    entity == null ? null : entity.getUuid(),
                    ClientCellPacketContext.sourceCell(), handoffState, continuousState,
                    entity == null ? null : entity.getVelocity(), packet.getVelocity(),
                    ignore ? "DROP" : "APPLY");
        }
        if (ignore) {
            ci.cancel();
        }
    }

    @Inject(method = "onEntityTrackerUpdate", at = @At("HEAD"), cancellable = true)
    private void largerworld$ignoreStaleTrackedData(
            EntityTrackerUpdateS2CPacket packet, CallbackInfo ci) {
        ClientWorld world = largerworld$worldOnClientThread();
        if (world == null) {
            return;
        }
        Entity entity = world.getEntityById(packet.id());
        boolean ignore = ClientEntityHandoff.shouldIgnoreTrackerUpdate(entity)
                || ClientContinuousEntityHandoff.shouldIgnoreTrackerUpdate(entity);
        if (ignore) {
            ci.cancel();
            return;
        }

        boolean targetTracker = ClientEntityHandoff.isTargetTrackerUpdate(entity)
                || ClientContinuousEntityHandoff.isTargetTrackerUpdate(entity);
        if (!targetTracker || entity == null) {
            return;
        }

        if (entity instanceof CamelEntity camel) {
            largerworld$debugCamelTracked(
                    "TARGET_TRACKED_BEFORE", camel, packet.trackedValues());
        }

        List<DataTracker.SerializedEntry<?>> filtered =
                largerworld$filterUnchangedTrackedData(entity, packet.trackedValues());
        if (filtered.size() == packet.trackedValues().size()) {
            return;
        }

        // Vanilla writes every entry and invokes onTrackedDataSet even when the
        // value is unchanged. Retained client entities already own those values;
        // replay only actual changes so subtype animation state is not restarted.
        ci.cancel();
        if (!filtered.isEmpty()) {
            entity.getDataTracker().writeUpdatedEntries(filtered);
        }
        if (entity instanceof CamelEntity camel) {
            largerworld$debugCamelTracked(
                    "TARGET_TRACKED_AFTER_MERGE", camel, filtered);
        }
    }

    @Inject(method = "onEntityTrackerUpdate", at = @At("RETURN"))
    private void largerworld$logTrackedDataAfterVanilla(
            EntityTrackerUpdateS2CPacket packet, CallbackInfo ci) {
        ClientWorld world = largerworld$worldOnClientThread();
        Entity entity = world == null ? null : world.getEntityById(packet.id());
        if (entity instanceof CamelEntity camel
                && (ClientEntityHandoff.isTargetTrackerUpdate(camel)
                || ClientContinuousEntityHandoff.isTargetTrackerUpdate(camel))) {
            largerworld$debugCamelTracked(
                    "TARGET_TRACKED_AFTER", camel, packet.trackedValues());
        }
    }

    @Unique
    private boolean largerworld$ignoreTrackerUpdate(int entityId) {
        ClientWorld world = largerworld$worldOnClientThread();
        if (world == null) {
            return false;
        }
        Entity entity = world.getEntityById(entityId);
        return ClientEntityHandoff.shouldIgnoreTrackerUpdate(entity)
                || ClientContinuousEntityHandoff.shouldIgnoreTrackerUpdate(entity);
    }

    @Unique
    private List<DataTracker.SerializedEntry<?>> largerworld$filterUnchangedTrackedData(
            Entity entity, List<DataTracker.SerializedEntry<?>> incoming) {
        DataTracker.Entry<?>[] current =
                ((DataTrackerAccessor) (Object) entity.getDataTracker())
                        .largerworld$getEntries();
        List<DataTracker.SerializedEntry<?>> filtered = new ArrayList<>(incoming.size());
        for (DataTracker.SerializedEntry<?> entry : incoming) {
            int id = entry.id();
            if (id >= 0
                    && id < current.length
                    && current[id] != null
                    && Objects.equals(current[id].get(), entry.value())) {
                continue;
            }
            filtered.add(entry);
        }
        return filtered;
    }

    @Unique
    private void largerworld$debugCamelTracked(
            String phase,
            CamelEntity camel,
            List<DataTracker.SerializedEntry<?>> entries) {
        Object incomingLastPose = entries.stream()
                .filter(entry -> entry.id() == CamelEntity.LAST_POSE_TICK.id())
                .map(DataTracker.SerializedEntry::value)
                .findFirst()
                .orElse(null);
        Largerworld.LOGGER.info(
                "[cross-camel-client] phase={} id={} source={} identityState={} "
                        + "continuousState={} worldTime={} pose={} sitting={} visualSitting={} "
                        + "changing={} currentLastPose={} incomingLastPose={} poseTime={} "
                        + "passengers={} entries={}",
                phase, camel.getId(), ClientCellPacketContext.sourceCell(),
                ClientEntityHandoff.debugState(camel.getId(), camel),
                ClientContinuousEntityHandoff.debugState(camel.getId(), camel),
                camel.getEntityWorld().getTime(), camel.getPose(), camel.isSitting(),
                camel.shouldUpdateSittingAnimations(), camel.isChangingPose(),
                camel.getDataTracker().get(CamelEntity.LAST_POSE_TICK),
                incomingLastPose, camel.getTimeSinceLastPoseTick(),
                camel.getPassengerList().size(), entries.size());
    }

    @Unique
    private ClientWorld largerworld$worldOnClientThread() {
        net.minecraft.client.MinecraftClient client =
                net.minecraft.client.MinecraftClient.getInstance();
        return client.isOnThread()
                ? ((ClientPlayNetworkHandler) (Object) this).getWorld()
                : null;
    }
}
