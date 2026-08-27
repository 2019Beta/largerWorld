package org.devt.largerworld.mixin;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.TargetPredicate;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.world.ServerWorld;
import org.devt.largerworld.world.CellBoundaryAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Applies target predicates with stitched distance and visibility for an adjacent cell. */
@Mixin(TargetPredicate.class)
public abstract class TargetPredicateMixin {
    @Shadow @Final private boolean attackable;
    @Shadow private double baseMaxDistance;
    @Shadow private boolean respectsVisibility;
    @Shadow private boolean useDistanceScalingFactor;
    @Shadow private TargetPredicate.EntityPredicate predicate;

    @Inject(method = "test", at = @At("HEAD"), cancellable = true)
    private void largerworld$testAcrossCell(
            ServerWorld world,
            LivingEntity observer,
            LivingEntity target,
            CallbackInfoReturnable<Boolean> cir) {
        if (observer == null || observer.getEntityWorld() == target.getEntityWorld()) {
            return;
        }
        CellBoundaryAccess.OptionalDoubleDistance distance =
                CellBoundaryAccess.squaredDistance(observer, target);
        if (!distance.present()) {
            return;
        }

        if (observer == target || !target.isPartOfGame()
                || predicate != null && !predicate.test(target, world)) {
            cir.setReturnValue(false);
            return;
        }
        if (attackable && (!observer.canTarget(target)
                || !observer.canTarget(target.getType())
                || observer.isTeammate(target))) {
            cir.setReturnValue(false);
            return;
        }
        if (baseMaxDistance > 0.0) {
            double scaling = useDistanceScalingFactor
                    ? target.getAttackDistanceScalingFactor(observer) : 1.0;
            double range = Math.max(baseMaxDistance * scaling, 2.0);
            if (distance.value() > range * range) {
                cir.setReturnValue(false);
                return;
            }
        }
        if (respectsVisibility && observer instanceof MobEntity mob
                && !mob.getVisibilityCache().canSee(target)) {
            cir.setReturnValue(false);
            return;
        }
        cir.setReturnValue(true);
    }
}
