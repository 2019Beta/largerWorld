package org.devt.largerworld.mixin;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;
import net.minecraft.world.EmptyBlockView;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkCache;
import org.devt.largerworld.coordinate.VirtualChunkPos;
import org.devt.largerworld.world.CellBoundaryAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Makes the bounded view used by vanilla pathfinding read through a cell seam. */
@Mixin(ChunkCache.class)
public abstract class ChunkCacheBoundaryMixin {
    @Shadow protected World world;

    @Inject(method = "getChunkAsView", at = @At("HEAD"), cancellable = true)
    private void largerworld$getNeighborChunkView(
            int chunkX, int chunkZ, CallbackInfoReturnable<BlockView> cir) {
        if (world instanceof ServerWorld serverWorld) {
            var resolved = CellBoundaryAccess.resolveLoadedChunkView(serverWorld, chunkX, chunkZ);
            if (resolved.isPresent()) {
                cir.setReturnValue(resolved.get());
            } else if (!VirtualChunkPos.isCanonical(chunkX, chunkZ)) {
                cir.setReturnValue(EmptyBlockView.INSTANCE);
            }
        }
    }

    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
    private void largerworld$getNeighborBlockState(
            BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        if (world instanceof ServerWorld serverWorld) {
            var resolved = CellBoundaryAccess.resolveLoadedBlock(serverWorld, pos);
            if (resolved.isPresent()) {
                CellBoundaryAccess.ResolvedBlock target = resolved.get();
                cir.setReturnValue(target.loadedChunk()
                        .map(chunk -> chunk.getBlockState(target.pos()))
                        .orElse(Blocks.AIR.getDefaultState()));
            } else if (!CellBoundaryAccess.isCanonical(pos)) {
                cir.setReturnValue(Blocks.AIR.getDefaultState());
            }
        }
    }

    @Inject(method = "getFluidState", at = @At("HEAD"), cancellable = true)
    private void largerworld$getNeighborFluidState(
            BlockPos pos, CallbackInfoReturnable<FluidState> cir) {
        if (world instanceof ServerWorld serverWorld) {
            var resolved = CellBoundaryAccess.resolveLoadedBlock(serverWorld, pos);
            if (resolved.isPresent()) {
                CellBoundaryAccess.ResolvedBlock target = resolved.get();
                cir.setReturnValue(target.loadedChunk()
                        .map(chunk -> chunk.getFluidState(target.pos()))
                        .orElse(Fluids.EMPTY.getDefaultState()));
            } else if (!CellBoundaryAccess.isCanonical(pos)) {
                cir.setReturnValue(Fluids.EMPTY.getDefaultState());
            }
        }
    }

    @Inject(method = "getBlockEntity", at = @At("HEAD"), cancellable = true)
    private void largerworld$getNeighborBlockEntity(
            BlockPos pos, CallbackInfoReturnable<BlockEntity> cir) {
        if (world instanceof ServerWorld serverWorld) {
            var resolved = CellBoundaryAccess.resolveLoadedBlock(serverWorld, pos);
            if (resolved.isPresent()) {
                CellBoundaryAccess.ResolvedBlock target = resolved.get();
                cir.setReturnValue(target.loadedChunk()
                        .map(chunk -> chunk.getBlockEntity(target.pos()))
                        .orElse(null));
            } else if (!CellBoundaryAccess.isCanonical(pos)) {
                cir.setReturnValue(null);
            }
        }
    }
}
