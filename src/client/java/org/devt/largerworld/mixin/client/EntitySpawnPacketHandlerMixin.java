package org.devt.largerworld.mixin.client;

import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.world.ClientWorld;
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
import java.util.Arrays;

/** Keeps a duplicate handoff spawn from replacing an already visible entity. */
@Mixin(ClientPlayNetworkHandler.class)
public abstract class EntitySpawnPacketHandlerMixin {
    @Unique
    private static final Map<Integer, EntityPassengersSetS2CPacket>
            largerworld$pendingPassengers = new HashMap<>();

    @Inject(method = "onEntitySpawn", at = @At("HEAD"), cancellable = true)
    private void largerworld$ignoreDuplicateSpawn(EntitySpawnS2CPacket packet, CallbackInfo ci) {
        ClientWorld world = ((ClientPlayNetworkHandler) (Object) this).getWorld();
        net.minecraft.entity.Entity existing = world == null
                ? null : world.getEntityById(packet.getEntityId());
        if (existing != null
                && existing.getUuid().equals(packet.getUuid())
                && ClientEntityHandoff.shouldKeep(existing)) {
            // This spawn describes the seam position at the instant the server
            // rebuilt the entity. It can arrive several movement packets later;
            // applying it would rewind the still-moving client vehicle and reset
            // its interpolation. The retained object is already at the correct
            // stitched-world position, and following tracker packets update its
            // data normally, so ignore the duplicate spawn in its entirety.
            Largerworld.LOGGER.info(
                    "[cell-transition] CLIENT_HANDOFF entityId={}",
                    packet.getEntityId());
            ci.cancel();
            return;
        }
        // Shadow tracking and destination tracking can legitimately announce
        // the same UUID twice. Only suppress that exact duplicate. An ID match
        // by itself is not sufficient: cancelling a different UUID leaves the
        // client with stale entities and can corrupt later tracking updates.
        if (ClientCellPacketContext.isApplyingCellPacket()
                && existing != null
                && existing.getUuid().equals(packet.getUuid())) {
            Largerworld.LOGGER.info(
                    "[cell-transition] CLIENT_KEEP_SPAWN entityId={}",
                    packet.getEntityId());
            ci.cancel();
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
        net.minecraft.entity.Entity vehicle = world == null
                ? null : world.getEntityById(packet.getEntityId());
        int[] currentPassengerIds = vehicle == null ? new int[0]
                : vehicle.getPassengerList().stream()
                .mapToInt(net.minecraft.entity.Entity::getId).toArray();
        if (ClientEntityHandoff.shouldKeep(vehicle)
                && Arrays.equals(currentPassengerIds, packet.getPassengerIds())) {
            // The retained client graph is already correct. Vanilla would
            // remove and re-add every passenger, which resets riding state and
            // creates the same one-frame pause that retaining the vehicle fixes.
            ci.cancel();
            return;
        }
        if (ClientCellPacketContext.isApplyingCellPacket()
                && (world == null || world.getEntityById(packet.getEntityId()) == null)) {
            largerworld$pendingPassengers.put(packet.getEntityId(), packet);
            Largerworld.LOGGER.info(
                    "[cell-transition] CLIENT_DEFER_PASSENGERS entityId={} passengers={}",
                    packet.getEntityId(), Arrays.toString(packet.getPassengerIds()));
            ci.cancel();
        }
    }

    @Inject(method = "onEntitySpawn", at = @At("RETURN"))
    private void largerworld$applyDeferredPassengers(
            EntitySpawnS2CPacket packet, CallbackInfo ci) {
        EntityPassengersSetS2CPacket pending =
                largerworld$pendingPassengers.remove(packet.getEntityId());
        if (pending != null) {
            Largerworld.LOGGER.info(
                    "[cell-transition] CLIENT_APPLY_PASSENGERS entityId={} passengers={}",
                    pending.getEntityId(), Arrays.toString(pending.getPassengerIds()));
            ((ClientPlayNetworkHandler) (Object) this)
                    .onEntityPassengersSet(pending);
        }
    }
}
