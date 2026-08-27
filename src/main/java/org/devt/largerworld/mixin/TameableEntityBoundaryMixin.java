package org.devt.largerworld.mixin;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.server.world.ServerWorld;
import org.devt.largerworld.world.CellBoundaryAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Lets tame animals walk through the seam before vanilla owner teleport is attempted. */
@Mixin(TameableEntity.class)
public abstract class TameableEntityBoundaryMixin {
    @Inject(method = "shouldTryTeleportToOwner", at = @At("RETURN"), cancellable = true)
    private void largerworld$doNotTeleportUsingForeignLocalCoordinates(
            CallbackInfoReturnable<Boolean> cir) {
        TameableEntity self = (TameableEntity) (Object) this;
        LivingEntity owner = self.getOwner();
        if (owner != null
                && self.getEntityWorld() instanceof ServerWorld
                && owner.getEntityWorld() != self.getEntityWorld()
                && CellBoundaryAccess.project(owner, self.getEntityWorld()).isPresent()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "tryTeleportToOwner", at = @At("HEAD"), cancellable = true)
    private void largerworld$cancelForeignLocalTeleport(CallbackInfo ci) {
        TameableEntity self = (TameableEntity) (Object) this;
        LivingEntity owner = self.getOwner();
        if (owner != null
                && owner.getEntityWorld() != self.getEntityWorld()
                && CellBoundaryAccess.project(owner, self.getEntityWorld()).isPresent()) {
            ci.cancel();
        }
    }
}
