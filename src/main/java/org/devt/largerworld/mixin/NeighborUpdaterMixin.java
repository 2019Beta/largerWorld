package org.devt.largerworld.mixin;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import net.minecraft.world.block.NeighborUpdater;
import net.minecraft.world.block.WireOrientation;
import org.devt.largerworld.server.CellPacketRouting;
import org.devt.largerworld.world.CellBoundaryAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Ensures six-way neighbor-update entries execute in the owning cell world. */
@Mixin(NeighborUpdater.class)
public interface NeighborUpdaterMixin {
    @Inject(
            method = "replaceWithStateForNeighborUpdate(Lnet/minecraft/world/WorldAccess;Lnet/minecraft/util/math/Direction;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;II)V",
            at = @At("HEAD"),
            cancellable = true)
    private static void largerworld$replaceWithStateAcrossCell(
            WorldAccess world,
            net.minecraft.util.math.Direction direction,
            BlockPos pos,
            BlockPos neighborPos,
            BlockState neighborState,
            int flags,
            int maxUpdateDepth,
            CallbackInfo ci) {
        if (world instanceof ServerWorld source) {
            CellBoundaryAccess.resolveLoadedBlock(source, pos).ifPresent(resolved -> {
                int offsetX = resolved.pos().getX() - pos.getX();
                int offsetZ = resolved.pos().getZ() - pos.getZ();
                BlockPos translatedNeighbor = neighborPos.add(offsetX, 0, offsetZ);
                CellPacketRouting.withSource(resolved.world(), () ->
                        NeighborUpdater.replaceWithStateForNeighborUpdate(
                                resolved.world(), direction, resolved.pos(), translatedNeighbor,
                                neighborState, flags, maxUpdateDepth));
                ci.cancel();
            });
        }
    }

    @Inject(method = "tryNeighborUpdate", at = @At("HEAD"), cancellable = true)
    private static void largerworld$tryNeighborUpdateAcrossCell(
            World world,
            BlockState state,
            BlockPos pos,
            Block sourceBlock,
            WireOrientation orientation,
            boolean notify,
            CallbackInfo ci) {
        if (world instanceof ServerWorld source) {
            CellBoundaryAccess.resolveLoadedBlock(source, pos).ifPresent(resolved -> {
                CellPacketRouting.withSource(resolved.world(), () ->
                        NeighborUpdater.tryNeighborUpdate(
                                resolved.world(),
                                state,
                                resolved.pos(),
                                sourceBlock,
                                orientation,
                                notify));
                ci.cancel();
            });
        }
    }
}
