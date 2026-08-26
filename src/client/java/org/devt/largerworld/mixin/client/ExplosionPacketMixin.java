package org.devt.largerworld.mixin.client;

import net.minecraft.network.packet.s2c.play.ExplosionS2CPacket;
import net.minecraft.util.math.Vec3d;
import org.devt.largerworld.client.network.ClientCellPacketContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ExplosionS2CPacket.class)
public abstract class ExplosionPacketMixin {
    @Inject(method = "center", at = @At("RETURN"), cancellable = true)
    private void largerworld$center(CallbackInfoReturnable<Vec3d> cir) {
        cir.setReturnValue(ClientCellPacketContext.position(cir.getReturnValue()));
    }
}
