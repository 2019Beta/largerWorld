package org.devt.largerworld.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.Packet;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import org.devt.largerworld.server.CellViewTracker;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerManager.class)
public abstract class PlayerManagerMixin {
    @Shadow @Final private MinecraftServer server;

    @Inject(method = "sendToAround", at = @At("RETURN"))
    private void largerworld$sendAcrossCells(
            PlayerEntity excluded,
            double x,
            double y,
            double z,
            double distance,
            RegistryKey<World> worldKey,
            Packet<?> packet,
            CallbackInfo ci) {
        ServerWorld sourceWorld = server.getWorld(worldKey);
        if (sourceWorld != null) {
            CellViewTracker.sendToShadowPlayers(
                    sourceWorld, (Entity) excluded, x, y, z, distance, packet);
        }
    }
}
