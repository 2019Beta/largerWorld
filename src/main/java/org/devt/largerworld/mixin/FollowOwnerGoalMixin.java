package org.devt.largerworld.mixin;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.FollowOwnerGoal;
import net.minecraft.entity.passive.TameableEntity;
import org.devt.largerworld.world.CellBoundaryAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Prevents a failed seam path from prematurely ending owner following. */
@Mixin(FollowOwnerGoal.class)
public abstract class FollowOwnerGoalMixin {
    @Shadow @Final private TameableEntity tameable;
    @Shadow private LivingEntity owner;
    @Shadow @Final private float maxDistance;

    @Inject(method = "shouldContinue", at = @At("RETURN"), cancellable = true)
    private void largerworld$continueAcrossCell(CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue() || owner == null || !owner.isAlive()
                || owner.getEntityWorld() == tameable.getEntityWorld()
                || tameable.cannotFollowOwner()) {
            return;
        }
        CellBoundaryAccess.OptionalDoubleDistance distance =
                CellBoundaryAccess.squaredDistance(tameable, owner);
        if (distance.present() && distance.value() > maxDistance * maxDistance) {
            cir.setReturnValue(true);
        }
    }
}
