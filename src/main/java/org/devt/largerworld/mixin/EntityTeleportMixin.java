package org.devt.largerworld.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.CamelEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.TeleportTarget;
import org.devt.largerworld.Largerworld;
import org.devt.largerworld.server.CellPacketRouting;
import org.devt.largerworld.server.SeamlessCellTeleport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Preserves continuous identity and per-entity momentum during a cell handoff. */
@Mixin(Entity.class)
public abstract class EntityTeleportMixin {
    @Shadow
    protected abstract void removeFromDimension();

    @Inject(method = "copyFrom", at = @At("RETURN"))
    private void largerworld$copyContinuousState(Entity original, CallbackInfo ci) {
        if (!SeamlessCellTeleport.isContinuousMovement()) {
            return;
        }
        Entity rebuilt = (Entity) (Object) this;
        rebuilt.setId(original.getId());
        rebuilt.setVelocity(original.getVelocity());

        if (original instanceof CamelEntity oldCamel
                && rebuilt instanceof CamelEntity newCamel) {
            long lastPoseTick = oldCamel.getDataTracker().get(CamelEntity.LAST_POSE_TICK);
            largerworld$debugCamel("COPY_SOURCE", oldCamel);
            largerworld$debugCamel("COPY_TARGET_BEFORE", newCamel);

            // Camel pose transitions are driven by both the generic pose and a
            // signed tracked timestamp. Preserve the exact timeline rather than
            // forcing the rebuilt camel immediately into a standing state.
            newCamel.setPose(oldCamel.getPose());
            newCamel.setLastPoseTick(lastPoseTick);

            largerworld$debugCamel("COPY_TARGET_AFTER", newCamel);
        }
    }

    @Unique
    private static void largerworld$debugCamel(String phase, CamelEntity camel) {
        Largerworld.logEntityInfo(
                "[cross-camel] phase={} id={} worldTime={} pose={} sitting={} "
                        + "visualSitting={} changing={} lastPoseTick={} poseTime={} passengers={}",
                phase, camel.getId(), camel.getEntityWorld().getTime(), camel.getPose(),
                camel.isSitting(), camel.shouldUpdateSittingAnimations(),
                camel.isChangingPose(),
                camel.getDataTracker().get(CamelEntity.LAST_POSE_TICK),
                camel.getTimeSinceLastPoseTick(), camel.getPassengerList().size());
    }

    @Inject(method = "getPassengerTeleportTarget", at = @At("RETURN"), cancellable = true)
    private void largerworld$preservePassengerVelocity(
            TeleportTarget rootTarget,
            Entity passenger,
            CallbackInfoReturnable<TeleportTarget> cir) {
        if (!SeamlessCellTeleport.isCellHandoff()) {
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

    @Redirect(
            method = "teleportCrossDimension",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/entity/Entity;removeFromDimension()V"))
    private void largerworld$routeSourceRemoval(Entity original) {
        if (SeamlessCellTeleport.isCellHandoff()
                && original.getEntityWorld() instanceof ServerWorld sourceWorld) {
            CellPacketRouting.withSource(sourceWorld, this::removeFromDimension);
        } else {
            removeFromDimension();
        }
    }
}
