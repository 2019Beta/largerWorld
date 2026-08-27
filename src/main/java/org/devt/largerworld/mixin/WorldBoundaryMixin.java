package org.devt.largerworld.mixin;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import org.devt.largerworld.world.CellBoundaryAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Redirects read-only block queries crossing a cell seam to the loaded neighboring cell. */
@Mixin(World.class)
public abstract class WorldBoundaryMixin {
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
