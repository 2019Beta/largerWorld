package org.devt.largerworld.mixin.client;

import net.minecraft.entity.EntityPosition;
import net.minecraft.network.packet.s2c.play.EntityPositionSyncS2CPacket;
import org.devt.largerworld.client.network.ClientCellPacketContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityPositionSyncS2CPacket.class)
public abstract class EntityPositionSyncPacketMixin {
    @Inject(method = "values", at = @At("RETURN"), cancellable = true)
    private void largerworld$position(CallbackInfoReturnable<EntityPosition> cir) {
        cir.setReturnValue(ClientCellPacketContext.entityPosition(cir.getReturnValue()));
    }
}
