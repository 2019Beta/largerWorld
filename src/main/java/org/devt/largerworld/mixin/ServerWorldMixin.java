package org.devt.largerworld.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.network.packet.s2c.play.BlockBreakingProgressS2CPacket;
import net.minecraft.network.packet.s2c.play.ParticleS2CPacket;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.devt.largerworld.server.CellPacketRouting;
import org.devt.largerworld.server.CellViewTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.BooleanSupplier;

@Mixin(ServerWorld.class)
public abstract class ServerWorldMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void largerworld$enterPacketSource(BooleanSupplier shouldKeepTicking, CallbackInfo ci) {
        CellPacketRouting.enterSource((ServerWorld) (Object) this);
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void largerworld$leavePacketSource(BooleanSupplier shouldKeepTicking, CallbackInfo ci) {
        CellPacketRouting.leaveSource();
    }

    @Inject(method = "setBlockBreakingInfo", at = @At("RETURN"))
    private void largerworld$sendBreakingAcrossCells(
            int entityId, BlockPos pos, int progress, CallbackInfo ci) {
        ServerWorld world = (ServerWorld) (Object) this;
        Entity excluded = world.getEntityById(entityId);
        CellViewTracker.sendToShadowPlayers(
                world, excluded, pos.getX(), pos.getY(), pos.getZ(), 32.0,
                new BlockBreakingProgressS2CPacket(entityId, pos, progress));
    }

    @Inject(
            method = "spawnParticles(Lnet/minecraft/particle/ParticleEffect;ZZDDDIDDDD)I",
            at = @At("RETURN"))
    private void largerworld$sendParticlesAcrossCells(
            ParticleEffect effect,
            boolean force,
            boolean important,
            double x,
            double y,
            double z,
            int count,
            double offsetX,
            double offsetY,
            double offsetZ,
            double speed,
            CallbackInfoReturnable<Integer> cir) {
        ServerWorld world = (ServerWorld) (Object) this;
        CellViewTracker.sendToShadowPlayers(
                world, null, x, y, z, force ? 512.0 : 32.0,
                new ParticleS2CPacket(effect, force, important, x, y, z,
                        (float) offsetX, (float) offsetY, (float) offsetZ, (float) speed, count));
    }
}
