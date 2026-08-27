package org.devt.largerworld.mixin;

import net.minecraft.block.BlockState;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.fluid.FluidState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.block.WireOrientation;
import org.devt.largerworld.server.CellPacketRouting;
import org.devt.largerworld.world.CellBoundaryAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


/** Redirects read-only block queries crossing a cell seam to the loaded neighboring cell. */
@Mixin(World.class)
public abstract class WorldBoundaryMixin {
    @Inject(method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;II)Z",
            at = @At("HEAD"), cancellable = true)
    private void largerworld$setNeighborBlockState(
            BlockPos pos, BlockState state, int flags, int maxUpdateDepth,
            CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof ServerWorld world) {
            CellBoundaryAccess.resolveLoadedBlock(world, pos).ifPresent(resolved ->
                    cir.setReturnValue(CellPacketRouting.withSourceResult(resolved.world(), () ->
                            resolved.world().setBlockState(
                                    resolved.pos(), state, flags, maxUpdateDepth))));
        }
    }

    @Inject(method = "removeBlock", at = @At("HEAD"), cancellable = true)
    private void largerworld$removeNeighborBlock(
            BlockPos pos, boolean move, CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof ServerWorld world) {
            CellBoundaryAccess.resolveLoadedBlock(world, pos).ifPresent(resolved ->
                    cir.setReturnValue(CellPacketRouting.withSourceResult(resolved.world(), () ->
                            resolved.world().removeBlock(resolved.pos(), move))));
        }
    }

    @Inject(method = "breakBlock", at = @At("HEAD"), cancellable = true)
    private void largerworld$breakNeighborBlock(
            BlockPos pos, boolean drop, Entity breakingEntity, int maxUpdateDepth,
            CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof ServerWorld world) {
            CellBoundaryAccess.resolveLoadedBlock(world, pos).ifPresent(resolved ->
                    cir.setReturnValue(CellPacketRouting.withSourceResult(resolved.world(), () ->
                            resolved.world().breakBlock(
                                    resolved.pos(), drop, breakingEntity, maxUpdateDepth))));
        }
    }

    @Inject(method = "removeBlockEntity", at = @At("HEAD"), cancellable = true)
    private void largerworld$removeNeighborBlockEntity(BlockPos pos, CallbackInfo ci) {
        if ((Object) this instanceof ServerWorld world) {
            CellBoundaryAccess.resolveLoadedBlock(world, pos).ifPresent(resolved -> {
                CellPacketRouting.withSource(resolved.world(), () ->
                        resolved.world().removeBlockEntity(resolved.pos()));
                ci.cancel();
            });
        }
    }

    @Inject(method = "updateNeighbor(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/Block;Lnet/minecraft/world/block/WireOrientation;)V",
            at = @At("HEAD"), cancellable = true)
    private void largerworld$updateNeighbor(
            BlockPos pos, Block sourceBlock, WireOrientation orientation, CallbackInfo ci) {
        if ((Object) this instanceof ServerWorld world) {
            CellBoundaryAccess.resolveLoadedBlock(world, pos).ifPresent(resolved -> {
                CellPacketRouting.withSource(resolved.world(), () ->
                        resolved.world().updateNeighbor(resolved.pos(), sourceBlock, orientation));
                ci.cancel();
            });
        }
    }

    @Inject(method = "updateNeighbor(Lnet/minecraft/block/BlockState;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/Block;Lnet/minecraft/world/block/WireOrientation;Z)V",
            at = @At("HEAD"), cancellable = true)
    private void largerworld$updateNeighborState(
            BlockState state,
            BlockPos pos,
            Block sourceBlock,
            WireOrientation orientation,
            boolean notify,
            CallbackInfo ci) {
        if ((Object) this instanceof ServerWorld world) {
            CellBoundaryAccess.resolveLoadedBlock(world, pos).ifPresent(resolved -> {
                CellPacketRouting.withSource(resolved.world(), () ->
                        resolved.world().updateNeighbor(
                                state, resolved.pos(), sourceBlock, orientation, notify));
                ci.cancel();
            });
        }
    }

    @Inject(method = "updateNeighborsExcept", at = @At("HEAD"), cancellable = true)
    private void largerworld$updateNeighborsExcept(
            BlockPos pos,
            Block sourceBlock,
            Direction except,
            WireOrientation orientation,
            CallbackInfo ci) {
        if ((Object) this instanceof ServerWorld world) {
            CellBoundaryAccess.resolveLoadedBlock(world, pos).ifPresent(resolved -> {
                CellPacketRouting.withSource(resolved.world(), () ->
                        resolved.world().updateNeighborsExcept(
                                resolved.pos(), sourceBlock, except, orientation));
                ci.cancel();
            });
        }
    }

    @Inject(method = "updateNeighborsAlways", at = @At("HEAD"), cancellable = true)
    private void largerworld$updateNeighborsAlways(
            BlockPos pos, Block sourceBlock, WireOrientation orientation, CallbackInfo ci) {
        if ((Object) this instanceof ServerWorld world) {
            CellBoundaryAccess.resolveLoadedBlock(world, pos).ifPresent(resolved -> {
                CellPacketRouting.withSource(resolved.world(), () ->
                        resolved.world().updateNeighborsAlways(
                                resolved.pos(), sourceBlock, orientation));
                ci.cancel();
            });
        }
    }

    @Inject(method = "addSyncedBlockEvent", at = @At("HEAD"), cancellable = true)
    private void largerworld$addNeighborSyncedBlockEvent(
            BlockPos pos, Block block, int type, int data, CallbackInfo ci) {
        if ((Object) this instanceof ServerWorld world) {
            CellBoundaryAccess.resolveLoadedBlock(world, pos).ifPresent(resolved -> {
                CellPacketRouting.withSource(resolved.world(), () ->
                        resolved.world().addSyncedBlockEvent(
                                resolved.pos(), block, type, data));
                ci.cancel();
            });
        }
    }

    @Inject(method = "getChunkAsView", at = @At("HEAD"), cancellable = true)
    private void largerworld$getNeighborChunkView(
            int chunkX, int chunkZ, CallbackInfoReturnable<BlockView> cir) {
        if ((Object) this instanceof ServerWorld world) {
            CellBoundaryAccess.resolveLoadedChunkView(world, chunkX, chunkZ)
                    .ifPresent(cir::setReturnValue);
        }
    }

    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
    private void largerworld$getNeighborBlockState(
            BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        if ((Object) this instanceof ServerWorld world) {
            CellBoundaryAccess.resolveLoadedBlock(world, pos)
                    .ifPresent(resolved -> cir.setReturnValue(resolved.world().getBlockState(resolved.pos())));
        }
    }

    @Inject(method = "getFluidState", at = @At("HEAD"), cancellable = true)
    private void largerworld$getNeighborFluidState(
            BlockPos pos, CallbackInfoReturnable<FluidState> cir) {
        if ((Object) this instanceof ServerWorld world) {
            CellBoundaryAccess.resolveLoadedBlock(world, pos)
                    .ifPresent(resolved -> cir.setReturnValue(resolved.world().getFluidState(resolved.pos())));
        }
    }

    @Inject(method = "getBlockEntity", at = @At("HEAD"), cancellable = true)
    private void largerworld$getNeighborBlockEntity(
            BlockPos pos, CallbackInfoReturnable<BlockEntity> cir) {
        if ((Object) this instanceof ServerWorld world) {
            CellBoundaryAccess.resolveLoadedBlock(world, pos)
                    .ifPresent(resolved -> cir.setReturnValue(resolved.world().getBlockEntity(resolved.pos())));
        }
    }
}
