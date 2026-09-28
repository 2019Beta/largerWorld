package org.devt.largerworld.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import org.devt.largerworld.world.CellBoundaryAccess;
import org.devt.largerworld.world.ProjectileBoundaryProjection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Projects neighboring-cell entity boxes during vanilla ray collision queries. */
@Mixin(ProjectileUtil.class)
public abstract class ProjectileUtilBoundaryMixin {
    @Redirect(
            method = "getEntityCollision(Lnet/minecraft/world/World;Lnet/minecraft/entity/Entity;Lnet/minecraft/util/math/Vec3d;Lnet/minecraft/util/math/Vec3d;Lnet/minecraft/util/math/Box;Ljava/util/function/Predicate;F)Lnet/minecraft/util/hit/EntityHitResult;",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/entity/Entity;getBoundingBox()Lnet/minecraft/util/math/Box;"))
    private static Box largerworld$projectEntityCollisionBox(
            Entity target,
            World world,
            Entity except,
            Vec3d start,
            Vec3d end,
            Box box,
            java.util.function.Predicate<? super Entity> predicate,
            float margin) {
        return projectedBoundingBox(target, world);
    }

    @Redirect(
            method = "raycast(Lnet/minecraft/entity/Entity;Lnet/minecraft/util/math/Vec3d;Lnet/minecraft/util/math/Vec3d;Lnet/minecraft/util/math/Box;Ljava/util/function/Predicate;D)Lnet/minecraft/util/hit/EntityHitResult;",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/entity/Entity;getBoundingBox()Lnet/minecraft/util/math/Box;"))
    private static Box largerworld$projectRaycastEntityBox(
            Entity target,
            Entity except,
            Vec3d start,
            Vec3d end,
            Box box,
            java.util.function.Predicate<? super Entity> predicate,
            double maxDistance) {
        return projectedBoundingBox(target, except.getEntityWorld());
    }

    @Redirect(
            method = "collectPiercingCollisions(Lnet/minecraft/world/World;Lnet/minecraft/entity/Entity;Lnet/minecraft/util/math/Vec3d;Lnet/minecraft/util/math/Vec3d;Lnet/minecraft/util/math/Box;Ljava/util/function/Predicate;FLnet/minecraft/world/RaycastContext$ShapeType;Z)Ljava/util/Collection;",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/entity/Entity;getBoundingBox()Lnet/minecraft/util/math/Box;",
                    ordinal = 0))
    private static Box largerworld$projectPiercingEntityBox(
            Entity target,
            World world,
            Entity except,
            Vec3d start,
            Vec3d end,
            Box box,
            java.util.function.Predicate<? super Entity> predicate,
            float margin,
            RaycastContext.ShapeType shapeType,
            boolean includeEntitiesContainingStart) {
        return projectedBoundingBox(target, world);
    }

    @Redirect(
            method = "collectPiercingCollisions(Lnet/minecraft/world/World;Lnet/minecraft/entity/Entity;Lnet/minecraft/util/math/Vec3d;Lnet/minecraft/util/math/Vec3d;Lnet/minecraft/util/math/Box;Ljava/util/function/Predicate;FLnet/minecraft/world/RaycastContext$ShapeType;Z)Ljava/util/Collection;",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/entity/Entity;getBoundingBox()Lnet/minecraft/util/math/Box;",
                    ordinal = 1))
    private static Box largerworld$projectPiercingOcclusionBox(
            Entity target,
            World world,
            Entity except,
            Vec3d start,
            Vec3d end,
            Box box,
            java.util.function.Predicate<? super Entity> predicate,
            float margin,
            RaycastContext.ShapeType shapeType,
            boolean includeEntitiesContainingStart) {
        return projectedBoundingBox(target, world);
    }

    @Unique
    private static Box projectedBoundingBox(Entity target, World sourceWorld) {
        Box localBox = target.getBoundingBox();
        if (sourceWorld == null || target.getEntityWorld() == sourceWorld
                || !(sourceWorld instanceof ServerWorld)) {
            return localBox;
        }

        return CellBoundaryAccess.project(target, sourceWorld)
                .map(projectedPosition -> ProjectileBoundaryProjection.translateBox(
                        localBox, target.getEntityPos(), projectedPosition))
                .orElse(localBox);
    }
}
