package org.devt.largerworld.mixin.client;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
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
import org.devt.largerworld.client.network.ClientEntityHandoff;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

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
        boolean matchingExisting = existing != null
                && existing.getUuid().equals(packet.getUuid())
                && ClientEntityHandoff.shouldIgnoreSpawn(existing);
        Largerworld.LOGGER.info(
                "[cell-handoff-client] SPAWN type={} id={} uuid={} source={} existingUuid={} "
                        + "velocity={} state={} decision={}",
                packet.getEntityType(), packet.getEntityId(), packet.getUuid(),
                ClientCellPacketContext.sourceCell(),
                existing == null ? null : existing.getUuid(), packet.getVelocity(),
                handoffState,
                matchingExisting ? "DROP" : "APPLY");
        if (matchingExisting) {
            // This spawn describes the seam position at the instant the server
            // rebuilt the entity. It can arrive several movement packets later;
            // applying it would rewind the still-moving client vehicle and reset
            // its interpolation. The retained object is already at the correct
            // stitched-world position, and following tracker packets update its
            // data normally, so ignore the duplicate spawn in its entirety.
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
            boolean ignore = ClientEntityHandoff.shouldIgnoreDestroy(entityId, entity);
            Largerworld.LOGGER.info(
                    "[cell-handoff-client] DESTROY type={} id={} uuid={} source={} state={} decision={}",
                    entity == null ? null : entity.getType(), entityId,
                    entity == null ? null : entity.getUuid(),
                    ClientCellPacketContext.sourceCell(), handoffState,
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
        boolean ignore = ClientEntityHandoff.shouldIgnorePassengerUpdate(
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
                    ignore ? "DROP" : "APPLY");
        }
        if (ignore) {
            // Cross-world teleportation temporarily detaches and then rebuilds
            // the same graph. The retained client graph is already correct, so
            // neither intermediate passenger list represents a real mount event.
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
        if (world != null
                && ClientEntityHandoff.shouldIgnoreTrackerUpdate(packet.getEntity(world))) {
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
        boolean ignore = ClientEntityHandoff.shouldIgnoreTrackerUpdate(entity);
        if (entity == null
                || entity instanceof ProjectileEntity
                || entity.hasPassengers()
                || !"NONE".equals(handoffState)) {
            Largerworld.LOGGER.info(
                    "[cross-velocity-client] type={} id={} uuid={} source={} state={} "
                            + "before={} packet={} decision={}",
                    entity == null ? null : entity.getType(), packet.getEntityId(),
                    entity == null ? null : entity.getUuid(),
                    ClientCellPacketContext.sourceCell(), handoffState,
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
        if (largerworld$ignoreTrackerUpdate(packet.id())) {
            ci.cancel();
        }
    }

    @Unique
    private boolean largerworld$ignoreTrackerUpdate(int entityId) {
        ClientWorld world = largerworld$worldOnClientThread();
        return world != null && ClientEntityHandoff.shouldIgnoreTrackerUpdate(
                world.getEntityById(entityId));
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
