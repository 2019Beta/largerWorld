package org.devt.largerworld.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.fluid.Fluid;
import net.minecraft.network.packet.s2c.play.BlockBreakingProgressS2CPacket;
import net.minecraft.network.packet.s2c.play.ParticleS2CPacket;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.block.WireOrientation;
import net.minecraft.world.tick.WorldTickScheduler;
import org.devt.largerworld.server.CellPacketRouting;
import org.devt.largerworld.server.CellTickSchedulerRouting;
import org.devt.largerworld.server.CellViewTracker;
import org.devt.largerworld.world.CellBoundaryAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.BooleanSupplier;

@Mixin(ServerWorld.class)
public abstract class ServerWorldMixin {
    @Inject(method = "getBlockTickScheduler()Lnet/minecraft/world/tick/WorldTickScheduler;",
            at = @At("RETURN"))
    private void largerworld$registerBlockTickScheduler(
            CallbackInfoReturnable<WorldTickScheduler<Block>> cir) {
        CellTickSchedulerRouting.register(
                cir.getReturnValue(),
                (ServerWorld) (Object) this,
                CellTickSchedulerRouting.Kind.BLOCK);
    }

    @Inject(method = "getFluidTickScheduler()Lnet/minecraft/world/tick/WorldTickScheduler;",
            at = @At("RETURN"))
    private void largerworld$registerFluidTickScheduler(
            CallbackInfoReturnable<WorldTickScheduler<Fluid>> cir) {
        CellTickSchedulerRouting.register(
                cir.getReturnValue(),
                (ServerWorld) (Object) this,
                CellTickSchedulerRouting.Kind.FLUID);
    }

    @Inject(method = "updateNeighborsAlways", at = @At("HEAD"), cancellable = true)
    private void largerworld$updateNeighborsAlwaysAcrossCell(
            BlockPos pos, Block sourceBlock, WireOrientation orientation, CallbackInfo ci) {
        ServerWorld source = (ServerWorld) (Object) this;
        CellBoundaryAccess.resolveLoadedBlock(source, pos).ifPresent(resolved -> {
            CellPacketRouting.withSource(resolved.world(), () ->
                    resolved.world().updateNeighborsAlways(
                            resolved.pos(), sourceBlock, orientation));
            ci.cancel();
        });
    }

    @Inject(method = "updateNeighbors", at = @At("HEAD"), cancellable = true)
    private void largerworld$updateNeighborsAcrossCell(
            BlockPos pos, Block sourceBlock, CallbackInfo ci) {
        ServerWorld source = (ServerWorld) (Object) this;
        CellBoundaryAccess.resolveLoadedBlock(source, pos).ifPresent(resolved -> {
            CellPacketRouting.withSource(resolved.world(), () ->
                    resolved.world().updateNeighbors(resolved.pos(), sourceBlock));
            ci.cancel();
        });
    }

    @Inject(method = "updateNeighborsExcept", at = @At("HEAD"), cancellable = true)
    private void largerworld$updateNeighborsExceptAcrossCell(
            BlockPos pos,
            Block sourceBlock,
            Direction except,
            WireOrientation orientation,
            CallbackInfo ci) {
        ServerWorld source = (ServerWorld) (Object) this;
        CellBoundaryAccess.resolveLoadedBlock(source, pos).ifPresent(resolved -> {
            CellPacketRouting.withSource(resolved.world(), () ->
                    resolved.world().updateNeighborsExcept(
                            resolved.pos(), sourceBlock, except, orientation));
            ci.cancel();
        });
    }

    @Inject(method = "updateNeighbor(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/Block;Lnet/minecraft/world/block/WireOrientation;)V",
            at = @At("HEAD"), cancellable = true)
    private void largerworld$updateNeighborAcrossCell(
            BlockPos pos, Block sourceBlock, WireOrientation orientation, CallbackInfo ci) {
        ServerWorld source = (ServerWorld) (Object) this;
        CellBoundaryAccess.resolveLoadedBlock(source, pos).ifPresent(resolved -> {
            CellPacketRouting.withSource(resolved.world(), () ->
                    resolved.world().updateNeighbor(
                            resolved.pos(), sourceBlock, orientation));
            ci.cancel();
        });
    }

    @Inject(method = "updateNeighbor(Lnet/minecraft/block/BlockState;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/Block;Lnet/minecraft/world/block/WireOrientation;Z)V",
            at = @At("HEAD"), cancellable = true)
    private void largerworld$updateNeighborStateAcrossCell(
            BlockState state,
            BlockPos pos,
            Block sourceBlock,
            WireOrientation orientation,
            boolean notify,
            CallbackInfo ci) {
        ServerWorld source = (ServerWorld) (Object) this;
        CellBoundaryAccess.resolveLoadedBlock(source, pos).ifPresent(resolved -> {
            CellPacketRouting.withSource(resolved.world(), () ->
                    resolved.world().updateNeighbor(
                            state, resolved.pos(), sourceBlock, orientation, notify));
            ci.cancel();
        });
    }

    @Inject(method = "addSyncedBlockEvent", at = @At("HEAD"), cancellable = true)
    private void largerworld$addSyncedBlockEventAcrossCell(
            BlockPos pos, Block block, int type, int data, CallbackInfo ci) {
        ServerWorld source = (ServerWorld) (Object) this;
        CellBoundaryAccess.resolveLoadedBlock(source, pos).ifPresent(resolved -> {
            CellPacketRouting.withSource(resolved.world(), () ->
                    resolved.world().addSyncedBlockEvent(
                            resolved.pos(), block, type, data));
            ci.cancel();
        });
    }

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
