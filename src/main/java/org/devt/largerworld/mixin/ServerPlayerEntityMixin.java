package org.devt.largerworld.mixin;

import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.TeleportTarget;
import org.devt.largerworld.server.SeamlessCellTeleport;
import org.devt.largerworld.server.CellInteractionRouting;
import org.devt.largerworld.server.CellPacketRouting;
import org.devt.largerworld.server.CellViewTracker;
import org.devt.largerworld.world.CellWorldKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.Redirect;
import net.minecraft.server.network.ServerPlayerInteractionManager;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.EntityPosition;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.network.packet.Packet;
import net.minecraft.server.network.ServerPlayNetworkHandler;

import java.util.Set;

@Mixin(ServerPlayerEntity.class)
public abstract class ServerPlayerEntityMixin {

    @Redirect(
            method = "dismountVehicle",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/network/ServerPlayNetworkHandler;sendPacket(Lnet/minecraft/network/packet/Packet;)V",
                    ordinal = 1))
    private void largerworld$skipTransientGraphDismount(
            ServerPlayNetworkHandler handler, Packet<?> packet) {
        ServerPlayerEntity player = (ServerPlayerEntity) (Object) this;
        if (SeamlessCellTeleport.isCellHandoff()
                && player.getRemovalReason() == Entity.RemovalReason.CHANGED_DIMENSION) {
            // Entity.setRemoved detaches children while moving the same riding
            // graph to another cell. The old vehicle is restored immediately;
            // sending this temporary empty passenger list would dismount the
            // client before the target relation arrives.
            return;
        }
        handler.sendPacket(packet);
    }

    @Redirect(
            method = "startRiding",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerPlayNetworkHandler;requestTeleport(Lnet/minecraft/entity/EntityPosition;Ljava/util/Set;)V"))
    private void largerworld$deferCellMountTeleport(
            ServerPlayNetworkHandler handler, EntityPosition position, Set<PositionFlag> flags) {
        if (CellInteractionRouting.isRerouting()
                || SeamlessCellTeleport.isContinuousMovement()) {
            // Remote interactions still use a temporary world projection here;
            // graph restoration also calls startRiding during a seamless move.
            // The passenger packet attaches the client to its retained vehicle.
            // Do not first teleport it to the server's newer seat snapshot or
            // leave a pending teleport acknowledgement in the temporary frame.
            return;
        }
        handler.requestTeleport(position, flags);
    }

    @Inject(method = "dismountVehicle", at = @At("HEAD"))
    private void largerworld$syncPositionBeforeDismount(CallbackInfo ci) {
        ServerPlayerEntity player = (ServerPlayerEntity) (Object) this;
        Entity vehicle = player.getVehicle();
        if (vehicle == null || SeamlessCellTeleport.isCellHandoff()
                || vehicle.getEntityWorld() != player.getEntityWorld()) {
            return;
        }
        // Riding movement updates the vehicle, while ordinary player movement
        // packets are ignored. Anchor the server player to the current seat
        // before the first on-foot packet is checked against its old position.
        vehicle.updatePassengerPosition(player);
        player.networkHandler.syncWithPlayerPosition();
    }

    @Inject(method = "openEditSignScreen", at = @At("HEAD"))
    private void largerworld$rememberRemoteSignEditor(
            SignBlockEntity sign, boolean front, CallbackInfo ci) {
        CellInteractionRouting.beginSignEdit((ServerPlayerEntity) (Object) this, sign);
    }

    @Redirect(
            method = "tick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerPlayerInteractionManager;update()V"))
    private void largerworld$updateRemoteMining(ServerPlayerInteractionManager interactionManager) {
        CellInteractionRouting.updateInteractionManager((ServerPlayerEntity) (Object) this, interactionManager);
    }

    @Redirect(
            method = "tick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/screen/ScreenHandler;canUse(Lnet/minecraft/entity/player/PlayerEntity;)Z"))
    private boolean largerworld$keepRemoteScreenOpen(ScreenHandler handler, PlayerEntity player) {
        return CellInteractionRouting.canUseScreen((ServerPlayerEntity) (Object) this, handler);
    }

    @Inject(method = "onHandledScreenClosed", at = @At("HEAD"), cancellable = true)
    private void largerworld$closeRemoteScreenInItsWorld(CallbackInfo ci) {
        if (CellInteractionRouting.closeRemoteScreen((ServerPlayerEntity) (Object) this)) {
            ci.cancel();
        }
    }

    @Inject(method = "teleportTo", at = @At("HEAD"), cancellable = true)
    private void largerworld$seamlessCellTeleport(
            TeleportTarget target, CallbackInfoReturnable<ServerPlayerEntity> cir) {
        ServerPlayerEntity player = (ServerPlayerEntity) (Object) this;
        if (player.isRemoved() || player.getEntityWorld() == target.world()) {
            return;
        }
        if (CellWorldKey.baseWorld(player.getEntityWorld().getRegistryKey())
                .equals(CellWorldKey.baseWorld(target.world().getRegistryKey()))) {
            CellPacketRouting.rebaseForDistantTeleport(
                    player, CellWorldKey.cell(target.world().getRegistryKey()));
            cir.setReturnValue(SeamlessCellTeleport.teleport(player, target));
        } else {
            CellViewTracker.resetForWorldChange(player);
        }
    }
}
