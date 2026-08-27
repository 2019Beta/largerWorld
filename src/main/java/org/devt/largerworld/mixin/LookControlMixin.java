package org.devt.largerworld.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.entity.ai.control.LookControl;
import net.minecraft.entity.mob.MobEntity;
import org.devt.largerworld.world.CellBoundaryAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Expresses entity look targets in the observer mob's cell coordinate frame. */
@Mixin(LookControl.class)
public abstract class LookControlMixin {
    @Shadow protected MobEntity entity;

    @Shadow public abstract void lookAt(double x, double y, double z);

    @Shadow public abstract void lookAt(
            double x, double y, double z, float maxYawChange, float maxPitchChange);

    @Inject(method = "lookAt(Lnet/minecraft/entity/Entity;)V",
            at = @At("HEAD"), cancellable = true)
    private void largerworld$lookAcrossCell(Entity target, CallbackInfo ci) {
        if (target.getEntityWorld() == entity.getEntityWorld()) {
            return;
        }
        CellBoundaryAccess.project(target, entity.getEntityWorld()).ifPresent(projected -> {
            lookAt(projected.x, projected.y + target.getStandingEyeHeight(), projected.z);
            ci.cancel();
        });
    }

    @Inject(method = "lookAt(Lnet/minecraft/entity/Entity;FF)V",
            at = @At("HEAD"), cancellable = true)
    private void largerworld$lookAcrossCell(
            Entity target, float maxYawChange, float maxPitchChange, CallbackInfo ci) {
        if (target.getEntityWorld() == entity.getEntityWorld()) {
            return;
        }
        CellBoundaryAccess.project(target, entity.getEntityWorld()).ifPresent(projected -> {
            lookAt(projected.x, projected.y + target.getStandingEyeHeight(), projected.z,
                    maxYawChange, maxPitchChange);
            ci.cancel();
        });
    }
}
