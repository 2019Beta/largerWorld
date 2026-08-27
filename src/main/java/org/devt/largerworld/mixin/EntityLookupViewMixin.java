package org.devt.largerworld.mixin;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.TargetPredicate;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.EntityLookupView;
import org.devt.largerworld.world.CellBoundaryAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/** Extends vanilla target lookup to players in immediately adjacent loaded cells. */
@Mixin(EntityLookupView.class)
public interface EntityLookupViewMixin {
    @Inject(method = "getClosestPlayer(Lnet/minecraft/entity/ai/TargetPredicate;Lnet/minecraft/entity/LivingEntity;)Lnet/minecraft/entity/player/PlayerEntity;",
            at = @At("RETURN"), cancellable = true)
    private void largerworld$getClosestPlayerAcrossCell(
            TargetPredicate predicate,
            LivingEntity observer,
            CallbackInfoReturnable<PlayerEntity> cir) {
        if (observer == null) {
            return;
        }
        ServerWorld source = ((EntityLookupView) this).toServerWorld();
        PlayerEntity closest = cir.getReturnValue();
        double closestDistance = closest == null
                ? Double.POSITIVE_INFINITY
                : observer.squaredDistanceTo(closest);
        for (ServerPlayerEntity candidate : source.getServer().getPlayerManager().getPlayerList()) {
            if (candidate.getEntityWorld() == source
                    || CellBoundaryAccess.project(candidate, source).isEmpty()
                    || !predicate.test(source, observer, candidate)) {
                continue;
            }
            CellBoundaryAccess.OptionalDoubleDistance distance =
                    CellBoundaryAccess.squaredDistance(observer, candidate);
            if (distance.present() && distance.value() < closestDistance) {
                closestDistance = distance.value();
                closest = candidate;
            }
        }
        if (closest != cir.getReturnValue()) {
            cir.setReturnValue(closest);
        }
    }

    @Inject(method = "getClosestPlayer(Lnet/minecraft/entity/ai/TargetPredicate;Lnet/minecraft/entity/LivingEntity;DDD)Lnet/minecraft/entity/player/PlayerEntity;",
            at = @At("RETURN"), cancellable = true)
    private void largerworld$getClosestPlayerAcrossCell(
            TargetPredicate predicate,
            LivingEntity observer,
            double x,
            double y,
            double z,
            CallbackInfoReturnable<PlayerEntity> cir) {
        if (observer == null) {
            return;
        }
        ServerWorld source = ((EntityLookupView) this).toServerWorld();
        PlayerEntity closest = cir.getReturnValue();
        double closestDistance = closest == null
                ? Double.POSITIVE_INFINITY
                : closest.squaredDistanceTo(x, y, z);

        for (ServerPlayerEntity candidate
                : source.getServer().getPlayerManager().getPlayerList()) {
            if (candidate.getEntityWorld() == source
                    || !predicate.test(source, observer, candidate)) {
                continue;
            }
            var projected = CellBoundaryAccess.project(candidate, source);
            if (projected.isEmpty()) {
                continue;
            }
            Vec3d position = projected.get();
            double dx = position.x - x;
            double dy = position.y - y;
            double dz = position.z - z;
            double distance = dx * dx + dy * dy + dz * dz;
            if (distance < closestDistance) {
                closestDistance = distance;
                closest = candidate;
            }
        }
        if (closest != cir.getReturnValue()) {
            cir.setReturnValue(closest);
        }
    }

    @Inject(method = "getPlayers(Lnet/minecraft/entity/ai/TargetPredicate;Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/util/math/Box;)Ljava/util/List;",
            at = @At("RETURN"), cancellable = true)
    private void largerworld$getPlayersAcrossCell(
            TargetPredicate predicate,
            LivingEntity observer,
            Box box,
            CallbackInfoReturnable<List<PlayerEntity>> cir) {
        if (observer == null) {
            return;
        }
        ServerWorld source = ((EntityLookupView) this).toServerWorld();
        List<PlayerEntity> result = new ArrayList<>(cir.getReturnValue());
        for (ServerPlayerEntity candidate
                : source.getServer().getPlayerManager().getPlayerList()) {
            if (candidate.getEntityWorld() == source
                    || !predicate.test(source, observer, candidate)) {
                continue;
            }
            CellBoundaryAccess.project(candidate, source).ifPresent(projected -> {
                if (box.contains(projected)) {
                    result.add(candidate);
                }
            });
        }
        if (result.size() != cir.getReturnValue().size()) {
            cir.setReturnValue(result);
        }
    }
}
