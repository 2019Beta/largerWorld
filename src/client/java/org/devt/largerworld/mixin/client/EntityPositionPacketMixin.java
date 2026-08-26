package org.devt.largerworld.mixin.client;

import net.minecraft.entity.EntityPosition;
import net.minecraft.network.packet.s2c.play.EntityPositionS2CPacket;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import org.devt.largerworld.client.network.ClientCellPacketContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

@Mixin(EntityPositionS2CPacket.class)
public abstract class EntityPositionPacketMixin {
    @Shadow public abstract Set<PositionFlag> relatives();

    @Inject(method = "change", at = @At("RETURN"), cancellable = true)
    private void largerworld$position(CallbackInfoReturnable<EntityPosition> cir) {
        cir.setReturnValue(ClientCellPacketContext.entityPosition(cir.getReturnValue(), relatives()));
    }
}
