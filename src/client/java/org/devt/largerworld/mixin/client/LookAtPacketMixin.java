package org.devt.largerworld.mixin.client;

import net.minecraft.network.packet.s2c.play.LookAtS2CPacket;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.devt.largerworld.client.network.ClientCellPacketContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LookAtS2CPacket.class)
public abstract class LookAtPacketMixin {
    @Shadow @Final private boolean lookAtEntity;
    @Shadow @Final private int entityId;

    @Inject(method = "getTargetPosition", at = @At("RETURN"), cancellable = true)
    private void largerworld$target(World world, CallbackInfoReturnable<Vec3d> cir) {
        Vec3d target = cir.getReturnValue();
        if (target != null && (!lookAtEntity || world.getEntityById(entityId) == null)) {
            cir.setReturnValue(ClientCellPacketContext.position(target));
        }
    }
}
