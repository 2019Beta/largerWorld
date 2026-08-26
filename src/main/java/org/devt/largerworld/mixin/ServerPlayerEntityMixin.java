package org.devt.largerworld.mixin;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.TeleportTarget;
import org.devt.largerworld.server.SeamlessCellTeleport;
import org.devt.largerworld.server.CellInteractionRouting;
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
            cir.setReturnValue(SeamlessCellTeleport.teleport(player, target));
        }
    }
}
