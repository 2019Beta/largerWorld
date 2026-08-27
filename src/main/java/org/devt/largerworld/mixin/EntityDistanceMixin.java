package org.devt.largerworld.mixin;

import net.minecraft.entity.Entity;
import org.devt.largerworld.world.CellBoundaryAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Makes entity-to-entity distance continuous across neighboring cells. */
@Mixin(Entity.class)
public abstract class EntityDistanceMixin {
    @Inject(method = "squaredDistanceTo(Lnet/minecraft/entity/Entity;)D", at = @At("HEAD"), cancellable = true)
    private void largerworld$squaredDistanceTo(Entity target, CallbackInfoReturnable<Double> cir) {
        CellBoundaryAccess.OptionalDoubleDistance distance = CellBoundaryAccess.squaredDistance(
                (Entity) (Object) this, target);
        if (distance.present()) {
            cir.setReturnValue(distance.value());
        }
    }

    @Inject(method = "distanceTo", at = @At("HEAD"), cancellable = true)
    private void largerworld$distanceTo(Entity target, CallbackInfoReturnable<Float> cir) {
        CellBoundaryAccess.OptionalDoubleDistance distance = CellBoundaryAccess.squaredDistance(
                (Entity) (Object) this, target);
        if (distance.present()) {
            cir.setReturnValue((float) Math.sqrt(distance.value()));
        }
    }
}
