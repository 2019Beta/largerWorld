package org.devt.largerworld.mixin;

import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.VehicleMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.JigsawGeneratingC2SPacket;
import net.minecraft.network.packet.c2s.play.SetTestBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.TestInstanceBlockActionC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateCommandBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateJigsawC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSignC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateStructureBlockC2SPacket;
import net.minecraft.server.filter.FilteredMessage;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.hit.BlockHitResult;
import org.devt.largerworld.server.CellPacketRouting;
import org.devt.largerworld.server.CellInteractionRouting;
import org.devt.largerworld.server.CellViewTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

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

    @Inject(method = "onSignUpdate", at = @At("HEAD"), cancellable = true)
    private void largerworld$routeSignUpdate(
            UpdateSignC2SPacket packet, List<FilteredMessage> messages, CallbackInfo ci) {
        if (CellInteractionRouting.handleSignUpdate(player, packet, messages)) {
            ci.cancel();
        }
    }

    @Inject(method = "onUpdateCommandBlock", at = @At("HEAD"), cancellable = true)
    private void largerworld$routeCommandBlock(UpdateCommandBlockC2SPacket packet, CallbackInfo ci) {
        var handler = (ServerPlayNetworkHandler) (Object) this;
        var server = player.getEntityWorld().getServer();
        if (server.isOnThread() && CellInteractionRouting.rerouteBlockPacket(
                server, handler, packet.getPos(), pos -> handler.onUpdateCommandBlock(
                        new UpdateCommandBlockC2SPacket(pos, packet.getCommand(), packet.getType(),
                                packet.shouldTrackOutput(), packet.isConditional(), packet.isAlwaysActive())))) {
            ci.cancel();
        }
    }

    @Inject(method = "onUpdateStructureBlock", at = @At("HEAD"), cancellable = true)
    private void largerworld$routeStructureBlock(UpdateStructureBlockC2SPacket packet, CallbackInfo ci) {
        var handler = (ServerPlayNetworkHandler) (Object) this;
        var server = player.getEntityWorld().getServer();
        if (server.isOnThread() && CellInteractionRouting.rerouteBlockPacket(
                server, handler, packet.getPos(), pos -> handler.onUpdateStructureBlock(
                        new UpdateStructureBlockC2SPacket(pos, packet.getAction(), packet.getMode(),
                                packet.getTemplateName(), packet.getOffset(), packet.getSize(),
                                packet.getMirror(), packet.getRotation(), packet.getMetadata(),
                                packet.shouldIgnoreEntities(), packet.isStrict(), packet.shouldShowAir(),
                                packet.shouldShowBoundingBox(), packet.getIntegrity(), packet.getSeed())))) {
            ci.cancel();
        }
    }

    @Inject(method = "onUpdateJigsaw", at = @At("HEAD"), cancellable = true)
    private void largerworld$routeJigsaw(UpdateJigsawC2SPacket packet, CallbackInfo ci) {
        var handler = (ServerPlayNetworkHandler) (Object) this;
        var server = player.getEntityWorld().getServer();
        if (server.isOnThread() && CellInteractionRouting.rerouteBlockPacket(
                server, handler, packet.getPos(), pos -> handler.onUpdateJigsaw(
                        new UpdateJigsawC2SPacket(pos, packet.getName(), packet.getTarget(), packet.getPool(),
                                packet.getFinalState(), packet.getJointType(),
                                packet.getSelectionPriority(), packet.getPlacementPriority())))) {
            ci.cancel();
        }
    }

    @Inject(method = "onJigsawGenerating", at = @At("HEAD"), cancellable = true)
    private void largerworld$routeJigsawGeneration(JigsawGeneratingC2SPacket packet, CallbackInfo ci) {
        var handler = (ServerPlayNetworkHandler) (Object) this;
        var server = player.getEntityWorld().getServer();
        if (server.isOnThread() && CellInteractionRouting.rerouteBlockPacket(
                server, handler, packet.getPos(), pos -> handler.onJigsawGenerating(
                        new JigsawGeneratingC2SPacket(pos, packet.getMaxDepth(), packet.shouldKeepJigsaws())))) {
            ci.cancel();
        }
    }

    @Inject(method = "onSetTestBlock", at = @At("HEAD"), cancellable = true)
    private void largerworld$routeTestBlock(SetTestBlockC2SPacket packet, CallbackInfo ci) {
        var handler = (ServerPlayNetworkHandler) (Object) this;
        var server = player.getEntityWorld().getServer();
        if (server.isOnThread() && CellInteractionRouting.rerouteBlockPacket(
                server, handler, packet.position(), pos -> handler.onSetTestBlock(
                        new SetTestBlockC2SPacket(pos, packet.mode(), packet.message())))) {
            ci.cancel();
        }
    }

    @Inject(method = "onTestInstanceBlockAction", at = @At("HEAD"), cancellable = true)
    private void largerworld$routeTestInstanceBlock(
            TestInstanceBlockActionC2SPacket packet, CallbackInfo ci) {
        var handler = (ServerPlayNetworkHandler) (Object) this;
        var server = player.getEntityWorld().getServer();
        if (server.isOnThread() && CellInteractionRouting.rerouteBlockPacket(
                server, handler, packet.pos(), pos -> handler.onTestInstanceBlockAction(
                        new TestInstanceBlockActionC2SPacket(pos, packet.action(), packet.data())))) {
            ci.cancel();
        }
    }

    @Redirect(
            method = {
                    "onRenameItem",
                    "onUpdateBeacon",
                    "onSelectMerchantTrade",
                    "onClickSlot",
                    "onCraftRequest",
                    "onButtonClick"
            },
            at = @At(value = "INVOKE", target = "Lnet/minecraft/screen/ScreenHandler;canUse(Lnet/minecraft/entity/player/PlayerEntity;)Z"))
    private boolean largerworld$useRemoteScreen(
            net.minecraft.screen.ScreenHandler handler,
            net.minecraft.entity.player.PlayerEntity ignoredPlayer) {
        return CellInteractionRouting.canUseScreen(player, handler);
    }

    @Inject(method = "onDisconnected", at = @At("HEAD"))
    private void largerworld$forgetDisconnectedPlayer(
            net.minecraft.network.DisconnectionInfo info, CallbackInfo ci) {
        CellInteractionRouting.closeRemoteScreen(player);
        CellViewTracker.forget(player);
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
