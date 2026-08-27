package org.devt.largerworld.mixin;

import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.VehicleMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.hit.BlockHitResult;
import org.devt.largerworld.server.CellPacketRouting;
import org.devt.largerworld.server.CellInteractionRouting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayNetworkHandler.class)
public abstract class ServerPlayNetworkHandlerMixin {
    @Shadow public ServerPlayerEntity player;

    @Inject(method = "onPlayerAction", at = @At("HEAD"), cancellable = true)
    private void largerworld$routeAction(PlayerActionC2SPacket packet, CallbackInfo ci) {
        var server = player.getEntityWorld().getServer();
        if (server.isOnThread() && CellInteractionRouting.reroutePlayerAction(
                server, (ServerPlayNetworkHandler) (Object) this, packet)) {
            ci.cancel();
        }
    }

    @Inject(method = "onPlayerInteractBlock", at = @At("HEAD"), cancellable = true)
    private void largerworld$routeBlockInteraction(PlayerInteractBlockC2SPacket packet, CallbackInfo ci) {
        var server = player.getEntityWorld().getServer();
        if (server.isOnThread() && CellInteractionRouting.rerouteBlockInteraction(
                server, (ServerPlayNetworkHandler) (Object) this, packet)) {
            ci.cancel();
        }
    }

    @Inject(method = "onPlayerInteractEntity", at = @At("HEAD"), cancellable = true)
    private void largerworld$routeEntityInteraction(PlayerInteractEntityC2SPacket packet, CallbackInfo ci) {
        var server = player.getEntityWorld().getServer();
        if (server.isOnThread() && CellInteractionRouting.rerouteEntityInteraction(
                (ServerPlayNetworkHandler) (Object) this, packet)) {
            ci.cancel();
        }
    }

    @Inject(method = "onCloseHandledScreen", at = @At("HEAD"))
    private void largerworld$closeRemoteScreen(
            net.minecraft.network.packet.c2s.play.CloseHandledScreenC2SPacket packet,
            CallbackInfo ci) {
        CellInteractionRouting.closeRemoteScreen(player);
    }

    @Redirect(
            method = "onPlayerMove",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/network/packet/c2s/play/PlayerMoveC2SPacket;getX(D)D"))
    private double largerworld$movementX(PlayerMoveC2SPacket packet, double fallback) {
        double value = packet.getX(fallback);
        return packet.changesPosition() ? CellPacketRouting.clientToLocalX(player, value) : value;
    }

    @Redirect(
            method = "onPlayerMove",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/network/packet/c2s/play/PlayerMoveC2SPacket;getZ(D)D"))
    private double largerworld$movementZ(PlayerMoveC2SPacket packet, double fallback) {
        double value = packet.getZ(fallback);
        return packet.changesPosition() ? CellPacketRouting.clientToLocalZ(player, value) : value;
    }

    @Redirect(
            method = "onVehicleMove",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/network/packet/c2s/play/VehicleMoveC2SPacket;position()Lnet/minecraft/util/math/Vec3d;"))
    private Vec3d largerworld$vehiclePosition(VehicleMoveC2SPacket packet) {
        Vec3d pos = packet.position();
        return new Vec3d(
                CellPacketRouting.clientToLocalX(player, pos.x),
                pos.y,
                CellPacketRouting.clientToLocalZ(player, pos.z));
    }

    @Redirect(
            method = "onPlayerAction",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/network/packet/c2s/play/PlayerActionC2SPacket;getPos()Lnet/minecraft/util/math/BlockPos;"))
    private BlockPos largerworld$actionPos(PlayerActionC2SPacket packet) {
        return CellInteractionRouting.isRerouting()
                ? packet.getPos()
                : CellPacketRouting.clientToLocal(player, packet.getPos());
    }

    @Redirect(
            method = "onPlayerInteractBlock",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/network/packet/c2s/play/PlayerInteractBlockC2SPacket;getBlockHitResult()Lnet/minecraft/util/hit/BlockHitResult;"))
    private BlockHitResult largerworld$interactionPos(PlayerInteractBlockC2SPacket packet) {
        return CellInteractionRouting.isRerouting()
                ? packet.getBlockHitResult()
                : CellPacketRouting.clientToLocal(player, packet.getBlockHitResult());
    }
}
