package org.devt.largerworld.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.world.ServerChunkLoadingManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.collection.BoundedRegionArray;
import net.minecraft.world.chunk.AbstractChunkHolder;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkGenerationStep;
import org.devt.largerworld.server.CellChunkTaskEngine;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.concurrent.CompletableFuture;

/** Applies global Cell-aware admission and write-set leases to real generation. */
@Mixin(ServerChunkLoadingManager.class)
public abstract class ServerChunkLoadingManagerGenerationMixin {
    @Shadow @Final private ServerWorld world;

    @WrapMethod(method = "generate")
    private CompletableFuture<Chunk> largerworld$coordinateGeneration(
            AbstractChunkHolder holder,
            ChunkGenerationStep step,
            BoundedRegionArray<AbstractChunkHolder> chunks,
            Operation<CompletableFuture<Chunk>> original) {
        return CellChunkTaskEngine.coordinateGeneration(
                world,
                holder.getPos(),
                step,
                () -> original.call(holder, step, chunks));
    }
}
