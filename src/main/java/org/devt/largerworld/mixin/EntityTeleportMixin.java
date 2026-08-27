package org.devt.largerworld.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.world.TeleportTarget;
import org.devt.largerworld.server.SeamlessCellTeleport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Preserves runtime identity and momentum while vanilla rebuilds an entity at a cell seam. */
@Mixin(Entity.class)
public abstract class EntityTeleportMixin {
    @Inject(method = "copyFrom", at = @At("RETURN"))
    private void largerworld$copyContinuousState(Entity original, CallbackInfo ci) {
        if (!SeamlessCellTeleport.isContinuousMovement()) {
            return;
        }
        Entity rebuilt = (Entity) (Object) this;
        rebuilt.setId(original.getId());
        rebuilt.setVelocity(original.getVelocity());
    }

    @Inject(method = "getPassengerTeleportTarget", at = @At("RETURN"), cancellable = true)
    private void largerworld$preservePassengerVelocity(
            TeleportTarget rootTarget,
            Entity passenger,
            CallbackInfoReturnable<TeleportTarget> cir) {
        if (!SeamlessCellTeleport.isContinuousMovement()) {
            return;
        }
        TeleportTarget target = cir.getReturnValue();
        cir.setReturnValue(new TeleportTarget(
                target.world(),
                target.position(),
                passenger.getVelocity(),
                target.yaw(),
                target.pitch(),
                target.missingRespawnBlock(),
                ((TeleportTargetAccessor) (Object) target).largerworld$isAsPassenger(),
                target.relatives(),
                target.postTeleportTransition()));
    }
}
