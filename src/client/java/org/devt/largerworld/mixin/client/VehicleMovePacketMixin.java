package org.devt.largerworld.mixin.client;

import net.minecraft.network.packet.s2c.play.VehicleMoveS2CPacket;
import net.minecraft.util.math.Vec3d;
import org.devt.largerworld.client.network.ClientCellPacketContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(VehicleMoveS2CPacket.class)
public abstract class VehicleMovePacketMixin {
    @Inject(method = "position", at = @At("RETURN"), cancellable = true)
    private void largerworld$position(CallbackInfoReturnable<Vec3d> cir) {
        cir.setReturnValue(ClientCellPacketContext.position(cir.getReturnValue()));
    }
}
