package org.devt.largerworld.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.entity.ai.pathing.EntityNavigation;
import net.minecraft.entity.ai.pathing.Path;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.devt.largerworld.server.CellNavigationWorldBinding;
import org.devt.largerworld.world.CellBoundaryAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Projects a cross-cell entity target into the navigation owner's coordinate frame. */
@Mixin(EntityNavigation.class)
public abstract class EntityNavigationMixin implements CellNavigationWorldBinding {
    @Shadow protected MobEntity entity;
    @Mutable @Shadow @Final protected World world;

    @Shadow public abstract Path findPathTo(BlockPos target, int distance);

    @Override
    public void largerworld$setNavigationWorld(World world) {
        this.world = world;
    }

    @Inject(method = "findPathTo(Lnet/minecraft/entity/Entity;I)Lnet/minecraft/entity/ai/pathing/Path;",
            at = @At("HEAD"), cancellable = true)
    private void largerworld$findPathAcrossCell(
            Entity target, int distance, CallbackInfoReturnable<Path> cir) {
        if (target.getEntityWorld() == entity.getEntityWorld()) {
            return;
        }
        CellBoundaryAccess.project(target, entity.getEntityWorld()).ifPresent(projected ->
                cir.setReturnValue(findPathTo(BlockPos.ofFloored(projected), distance)));
    }

    @Inject(method = "startMovingTo(Lnet/minecraft/entity/Entity;D)Z",
            at = @At("RETURN"), cancellable = true)
    private void largerworld$moveDirectlyWhenSeamPathFails(
            Entity target, double speed, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue() || target.getEntityWorld() == entity.getEntityWorld()) {
            return;
        }
        CellBoundaryAccess.project(target, entity.getEntityWorld()).ifPresent(projected -> {
            entity.getMoveControl().moveTo(projected.x, projected.y, projected.z, speed);
            cir.setReturnValue(true);
        });
    }

}
