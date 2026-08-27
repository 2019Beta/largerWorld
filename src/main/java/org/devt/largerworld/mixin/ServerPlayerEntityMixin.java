package org.devt.largerworld.mixin;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.TeleportTarget;
import org.devt.largerworld.server.SeamlessCellTeleport;
import org.devt.largerworld.server.CellInteractionRouting;
import org.devt.largerworld.server.CellPacketRouting;
import org.devt.largerworld.world.CellWorldKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.Redirect;
import net.minecraft.server.network.ServerPlayerInteractionManager;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.entity.player.PlayerEntity;

@Mixin(ServerPlayerEntity.class)
public abstract class ServerPlayerEntityMixin {

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
    @Inject(method = "teleportTo", at = @At("HEAD"), cancellable = true)
    private void largerworld$seamlessCellTeleport(
            TeleportTarget target, CallbackInfoReturnable<ServerPlayerEntity> cir) {
        ServerPlayerEntity player = (ServerPlayerEntity) (Object) this;
        if (player.isRemoved() || player.getEntityWorld() == target.world()) {
            return;
        }
        if (CellWorldKey.baseWorld(player.getEntityWorld().getRegistryKey())
                .equals(CellWorldKey.baseWorld(target.world().getRegistryKey()))) {
            // A stable client origin cannot represent arbitrarily distant cells:
            // vanilla clamps entity positions at 30 million and BlockPos is int.
            // Rebase before the first target-world packet and let vanilla send a
            // respawn packet so stale chunks/entities from the old origin vanish.
            if (CellPacketRouting.rebaseForDistantTeleport(
                    player, CellWorldKey.cell(target.world().getRegistryKey()))) {
                return;
            }
            cir.setReturnValue(SeamlessCellTeleport.teleport(player, target));
        }
    }
}
