package org.devt.largerworld.mixin;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.player.PlayerEntity;
import org.devt.largerworld.world.CellBoundaryAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keeps melee pursuit alive while its target is crossing an adjacent cell seam. */
@Mixin(MeleeAttackGoal.class)
public abstract class MeleeAttackGoalMixin {
    @Shadow @Final protected PathAwareEntity mob;

    @Inject(method = "canStart", at = @At("RETURN"), cancellable = true)
    private void largerworld$startAcrossCell(CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValue() && largerworld$hasValidCrossCellTarget()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "shouldContinue", at = @At("RETURN"), cancellable = true)
    private void largerworld$continueAcrossCell(CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValue() && largerworld$hasValidCrossCellTarget()) {
            cir.setReturnValue(true);
        }
    }

    private boolean largerworld$hasValidCrossCellTarget() {
        LivingEntity target = mob.getTarget();
        if (target == null || !target.isAlive()
                || target.getEntityWorld() == mob.getEntityWorld()
                || target instanceof PlayerEntity player
                && (player.isCreative() || player.isSpectator())) {
            return false;
        }
        return CellBoundaryAccess.project(target, mob.getEntityWorld()).isPresent();
    }
}
