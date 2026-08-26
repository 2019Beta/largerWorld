package org.devt.largerworld.mixin.client;

import net.minecraft.network.packet.s2c.play.WorldBorderCenterChangedS2CPacket;
import net.minecraft.network.packet.s2c.play.WorldBorderInitializeS2CPacket;
import org.devt.largerworld.client.network.ClientCellPacketContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin({WorldBorderCenterChangedS2CPacket.class, WorldBorderInitializeS2CPacket.class})
public abstract class WorldBorderPacketMixin {
    @Inject(method = "getCenterX", at = @At("RETURN"), cancellable = true)
    private void largerworld$centerX(CallbackInfoReturnable<Double> cir) {
        cir.setReturnValue(ClientCellPacketContext.x(cir.getReturnValue()));
    }

    @Inject(method = "getCenterZ", at = @At("RETURN"), cancellable = true)
    private void largerworld$centerZ(CallbackInfoReturnable<Double> cir) {
        cir.setReturnValue(ClientCellPacketContext.z(cir.getReturnValue()));
    }
}
