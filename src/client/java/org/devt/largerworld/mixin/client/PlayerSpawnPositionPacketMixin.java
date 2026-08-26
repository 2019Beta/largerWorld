package org.devt.largerworld.mixin.client;

import net.minecraft.network.packet.s2c.play.PlayerSpawnPositionS2CPacket;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.world.WorldProperties;
import org.devt.largerworld.client.network.ClientCellPacketContext;
import org.devt.largerworld.world.CellWorldKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerSpawnPositionS2CPacket.class)
public abstract class PlayerSpawnPositionPacketMixin {
    @Inject(method = "respawnData", at = @At("RETURN"), cancellable = true)
    private void largerworld$spawn(CallbackInfoReturnable<WorldProperties.SpawnPoint> cir) {
        WorldProperties.SpawnPoint original = cir.getReturnValue();
        cir.setReturnValue(new WorldProperties.SpawnPoint(
                GlobalPos.create(original.getDimension(),
                        ClientCellPacketContext.blockPos(
                                CellWorldKey.cell(original.getDimension()), original.getPos())),
                original.yaw(),
                original.pitch()));
    }
}
