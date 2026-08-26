package org.devt.largerworld.mixin.client;

import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.network.packet.s2c.play.ParticleS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import org.devt.largerworld.client.network.ClientCellPacketContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin({EntitySpawnS2CPacket.class, ParticleS2CPacket.class, PlaySoundS2CPacket.class})
public abstract class DoublePositionPacketMixin {
    @Inject(method = "getX", at = @At("RETURN"), cancellable = true)
    private void largerworld$x(CallbackInfoReturnable<Double> cir) {
        cir.setReturnValue(ClientCellPacketContext.x(cir.getReturnValue()));
    }

    @Inject(method = "getZ", at = @At("RETURN"), cancellable = true)
    private void largerworld$z(CallbackInfoReturnable<Double> cir) {
        cir.setReturnValue(ClientCellPacketContext.z(cir.getReturnValue()));
    }
}
