package org.devt.largerworld.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.TeleportTarget;
import org.devt.largerworld.server.CellPacketRouting;
import org.devt.largerworld.server.SeamlessCellTeleport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
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
