package org.devt.largerworld.mixin;

import net.minecraft.server.world.OptionalChunk;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.concurrent.CompletableFuture;

/** Exposes the non-blocking chunk future without pumping the server executor. */
@Mixin(ServerChunkManager.class)
public interface ServerChunkManagerAccessor {
    @Invoker("getChunkFuture")
    CompletableFuture<OptionalChunk<Chunk>> largerworld$getChunkFuture(
            int chunkX, int chunkZ, ChunkStatus status, boolean create);
}
