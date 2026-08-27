package org.devt.largerworld.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.border.WorldBorder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Makes the vanilla world border informationally and physically absent. */
@Mixin(WorldBorder.class)
public abstract class WorldBorderMixin {
    @Inject(method = "contains(Lnet/minecraft/util/math/BlockPos;)Z", at = @At("HEAD"), cancellable = true)
    private void largerworld$containsBlock(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(true);
    }

    @Inject(method = "contains(Lnet/minecraft/util/math/Vec3d;)Z", at = @At("HEAD"), cancellable = true)
    private void largerworld$containsPosition(Vec3d pos, CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(true);
    }

    @Inject(method = "contains(Lnet/minecraft/util/math/ChunkPos;)Z", at = @At("HEAD"), cancellable = true)
    private void largerworld$containsChunk(ChunkPos pos, CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(true);
    }

    @Inject(method = "contains(Lnet/minecraft/util/math/Box;)Z", at = @At("HEAD"), cancellable = true)
    private void largerworld$containsBox(Box box, CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(true);
    }

    @Inject(method = "contains(DD)Z", at = @At("HEAD"), cancellable = true)
    private void largerworld$containsCoordinates(double x, double z, CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(true);
    }

    @Inject(method = "contains(DDD)Z", at = @At("HEAD"), cancellable = true)
    private void largerworld$containsCoordinatesWithMargin(
            double x, double z, double margin, CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(true);
    }

    @Inject(method = "canCollide", at = @At("HEAD"), cancellable = true)
    private void largerworld$disableCollision(Entity entity, Box box, CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(false);
    }

    @Inject(method = "asVoxelShape", at = @At("HEAD"), cancellable = true)
    private void largerworld$emptyCollisionShape(CallbackInfoReturnable<VoxelShape> cir) {
        cir.setReturnValue(VoxelShapes.empty());
    }

    @Inject(method = "getDistanceInsideBorder(DD)D", at = @At("HEAD"), cancellable = true)
    private void largerworld$infiniteDistance(double x, double z, CallbackInfoReturnable<Double> cir) {
        cir.setReturnValue(Double.POSITIVE_INFINITY);
    }
}
