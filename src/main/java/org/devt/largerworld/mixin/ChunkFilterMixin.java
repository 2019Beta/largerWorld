package org.devt.largerworld.mixin;

import net.minecraft.server.network.ChunkFilter;
import org.devt.largerworld.coordinate.VirtualChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keeps vanilla view tracking inside the current backing cell. */
@Mixin(ChunkFilter.Cylindrical.class)
public abstract class ChunkFilterMixin {
    @Inject(method = "isWithinDistance(IIZ)Z", at = @At("HEAD"), cancellable = true)
    private void largerworld$clipToCell(
            int x, int z, boolean includeEdge, CallbackInfoReturnable<Boolean> cir) {
        if (x < -VirtualChunkPos.HALF_CELL_CHUNKS || x >= VirtualChunkPos.HALF_CELL_CHUNKS
                || z < -VirtualChunkPos.HALF_CELL_CHUNKS || z >= VirtualChunkPos.HALF_CELL_CHUNKS) {
            cir.setReturnValue(false);
        }
    }
}
