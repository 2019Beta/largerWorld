package org.devt.largerworld.mixin.client;

import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import org.devt.largerworld.client.network.ClientCellPacketContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps a duplicate handoff spawn from replacing an already visible entity. */
@Mixin(ClientPlayNetworkHandler.class)
public abstract class EntitySpawnPacketHandlerMixin {
    @Inject(method = "onEntitySpawn", at = @At("HEAD"), cancellable = true)
    private void largerworld$ignoreDuplicateSpawn(EntitySpawnS2CPacket packet, CallbackInfo ci) {
        ClientWorld world = ((ClientPlayNetworkHandler) (Object) this).getWorld();
        if (ClientCellPacketContext.isApplyingCellPacket()
                && world != null
                && world.getEntityById(packet.getEntityId()) != null) {
            ci.cancel();
        }
    }
}
