package org.devt.largerworld.mixin.client;

import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityPassengersSetS2CPacket;
import org.devt.largerworld.Largerworld;
import org.devt.largerworld.client.network.ClientCellPacketContext;
import org.devt.largerworld.client.network.ClientEntityHandoff;
import it.unimi.dsi.fastutil.ints.IntConsumer;
import it.unimi.dsi.fastutil.ints.IntList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
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
        if ((ClientCellPacketContext.isApplyingCellPacket()
                || ClientEntityHandoff.isActive(packet.getEntityId()))
                && world != null
                && world.getEntityById(packet.getEntityId()) != null) {
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
        if (ClientEntityHandoff.isActive(packet.getEntityId())) {
            Largerworld.LOGGER.info(
                    "[cell-transition] CLIENT_KEEP_PASSENGERS entityId={} passengers={}",
                    packet.getEntityId(), Arrays.toString(packet.getPassengerIds()));
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

    @Redirect(
            method = "onEntitiesDestroy",
            at = @At(
                    value = "INVOKE",
                    target = "Lit/unimi/dsi/fastutil/ints/IntList;forEach(Lit/unimi/dsi/fastutil/ints/IntConsumer;)V"))
    private void largerworld$preserveHandoffEntities(
            IntList entityIds, IntConsumer removeEntity) {
        for (int entityId : entityIds) {
            if (ClientEntityHandoff.preserveDestroy(entityId)) {
                Largerworld.LOGGER.info(
                        "[cell-transition] CLIENT_KEEP_ENTITY entityId={}", entityId);
            } else {
                removeEntity.accept(entityId);
            }
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
