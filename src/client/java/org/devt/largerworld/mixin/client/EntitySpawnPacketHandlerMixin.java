package org.devt.largerworld.mixin.client;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.s2c.play.EntitiesDestroyS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityPassengersSetS2CPacket;
import org.devt.largerworld.Largerworld;
import org.devt.largerworld.client.network.ClientCellPacketContext;
import org.devt.largerworld.client.network.ClientEntityHandoff;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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
        if (existing != null
                && existing.getUuid().equals(packet.getUuid())
                && ClientEntityHandoff.shouldIgnoreSpawn(existing)) {
            Largerworld.LOGGER.debug(
                    "[cell-handoff] Retained target entity id={} uuid={}",
                    existing.getId(), existing.getUuid());
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
            if (ClientEntityHandoff.shouldIgnoreDestroy(entity)) {
                Largerworld.LOGGER.debug(
                        "[cell-handoff] Retained source entity id={} uuid={}",
                        entity.getId(), entity.getUuid());
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
        if (ClientEntityHandoff.shouldIgnorePassengerUpdate(
                vehicle, packet.getPassengerIds())) {
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
}
