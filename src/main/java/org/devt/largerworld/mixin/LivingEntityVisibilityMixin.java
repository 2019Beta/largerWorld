package org.devt.largerworld.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.devt.largerworld.world.CellBoundaryAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Performs visibility rays in the observer's stitched coordinate frame. */
@Mixin(LivingEntity.class)
public abstract class LivingEntityVisibilityMixin {
    @Inject(method = "canSee(Lnet/minecraft/entity/Entity;Lnet/minecraft/world/RaycastContext$ShapeType;Lnet/minecraft/world/RaycastContext$FluidHandling;D)Z",
            at = @At("HEAD"), cancellable = true)
    private void largerworld$canSeeAcrossCell(
            Entity target,
            RaycastContext.ShapeType shapeType,
            RaycastContext.FluidHandling fluidHandling,
            double targetY,
            CallbackInfoReturnable<Boolean> cir) {
        LivingEntity observer = (LivingEntity) (Object) this;
        if (target.getEntityWorld() == observer.getEntityWorld()) {
            return;
        }
        CellBoundaryAccess.project(target, observer.getEntityWorld()).ifPresent(projected -> {
            Vec3d start = new Vec3d(observer.getX(), observer.getEyeY(), observer.getZ());
            Vec3d end = new Vec3d(projected.x, targetY, projected.z);
            cir.setReturnValue(end.distanceTo(start) <= 128.0
                    && observer.getEntityWorld().raycast(new RaycastContext(
                    start, end, shapeType, fluidHandling, observer)).getType() == HitResult.Type.MISS);
        });
    }
}
