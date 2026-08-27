package org.devt.largerworld.mixin;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
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
