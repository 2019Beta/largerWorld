package org.devt.largerworld.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.entity.Leashable;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import org.devt.largerworld.world.CellBoundaryAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/** Supplies the leash pull that vanilla skips when holder and held entity use adjacent cell worlds. */
@Mixin(Leashable.class)
public interface LeashableMixin {
    @Redirect(
            method = "resolveLeashData",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/world/ServerWorld;getEntity(Ljava/util/UUID;)Lnet/minecraft/entity/Entity;"))
    private static Entity largerworld$resolveHolderAcrossCell(
            ServerWorld world, UUID uuid) {
        return world.getEntityAnyDimension(uuid);
    }

    @Inject(method = "tickLeash", at = @At("RETURN"))
    private static void largerworld$tickAcrossCell(
            ServerWorld world, Entity held, CallbackInfo ci) {
        if (!(held instanceof Leashable leashable)) {
            return;
        }
        Entity holder = leashable.getLeashHolder();
        if (holder == null || holder.getEntityWorld() == held.getEntityWorld()) {
            return;
        }
        CellBoundaryAccess.project(holder, held.getEntityWorld()).ifPresent(projected -> {
            Vec3d delta = projected.subtract(held.getEntityPos());
            double distance = delta.length();
            if (distance <= leashable.getElasticLeashDistance() || distance < 1.0E-6) {
                return;
            }
            double acceleration = Math.min(0.25,
                    (distance - leashable.getElasticLeashDistance()) * 0.05);
            held.addVelocityInternal(delta.multiply(acceleration / distance));
            held.limitFallDistance();
            if (held instanceof MobEntity mob) {
                mob.getMoveControl().moveTo(projected.x, projected.y, projected.z, 1.0);
            }
        });
    }
}
