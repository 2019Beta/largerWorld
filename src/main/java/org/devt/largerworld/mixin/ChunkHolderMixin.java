package org.devt.largerworld.mixin;

import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.chunk.WorldChunk;
import org.devt.largerworld.server.CellViewTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkHolder.class)
public abstract class ChunkHolderMixin {
    @Inject(method = "flushUpdates", at = @At("HEAD"))
    private void largerworld$refreshShadowChunk(WorldChunk chunk, CallbackInfo ci) {
        if (chunk.getWorld() instanceof ServerWorld world) {
            CellViewTracker.broadcastChunkRefresh(world, chunk);
        }
    }
}
