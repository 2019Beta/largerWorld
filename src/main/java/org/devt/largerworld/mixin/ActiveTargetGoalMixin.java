package org.devt.largerworld.mixin;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.TargetPredicate;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.ai.goal.ActiveTargetGoal;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import org.devt.largerworld.world.CellBoundaryAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Lets the standard active-target goal discover entities in a loaded adjacent cell. */
@Mixin(ActiveTargetGoal.class)
public abstract class ActiveTargetGoalMixin {
    @Shadow protected Class<? extends LivingEntity> targetClass;
    @Shadow protected LivingEntity targetEntity;
    @Shadow protected TargetPredicate targetPredicate;

    @Shadow protected abstract Box getSearchBox(double distance);

    @Inject(method = "findClosestTarget", at = @At("RETURN"))
    private void largerworld$findClosestAcrossCell(CallbackInfo ci) {
        MobEntity mob = ((TrackTargetGoalAccessor) (Object) this).largerworld$getMob();
        if (targetEntity != null || !(mob.getEntityWorld() instanceof ServerWorld source)) {
            return;
        }
        double followRange = mob.getAttributeValue(EntityAttributes.FOLLOW_RANGE);
        if (!Double.isFinite(followRange) || followRange < 0.0) {
            return;
        }
        // ActiveTargetGoal inherits getFollowRange() from TrackTargetGoal. Keep
        // the inherited method out of the shadow list and mirror its behavior.
        targetPredicate.setBaseMaxDistance(followRange);
        Box searchBox = getSearchBox(followRange);
        double closestDistance = Double.POSITIVE_INFINITY;
        LivingEntity closest = null;
        for (CellBoundaryAccess.ProjectedWorld projected
                : CellBoundaryAccess.loadedWorldsOverlapping(source, searchBox)) {
            for (LivingEntity candidate : projected.world().getEntitiesByClass(
                    targetClass, projected.localBox(), entity -> true)) {
                if (!targetPredicate.test(source, mob, candidate)) {
                    continue;
                }
                CellBoundaryAccess.OptionalDoubleDistance distance =
                        CellBoundaryAccess.squaredDistance(mob, candidate);
                if (distance.present() && distance.value() < closestDistance) {
                    closestDistance = distance.value();
                    closest = candidate;
                }
            }
        }
        targetEntity = closest;
    }
}
