package org.devt.largerworld.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerChunkLoadingManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.WorldChunk;
import org.devt.largerworld.server.CellChunkIoQueue;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** Coalesces Region writes and keeps unloading entities behind a write barrier. */
@Mixin(ServerChunkLoadingManager.class)
public abstract class ServerChunkLoadingManagerWriteIoMixin {
    @Shadow @Final private ServerWorld world;

    @WrapOperation(
            method = "save(Lnet/minecraft/world/chunk/Chunk;)Z",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/concurrent/CompletableFuture;supplyAsync(Ljava/util/function/Supplier;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<NbtCompound> largerworld$deferChunkSerialization(
            Supplier<NbtCompound> serializer,
            Executor executor,
            Operation<CompletableFuture<NbtCompound>> original) {
        return CellChunkIoQueue.lazySerialize(serializer, executor);
    }

    @WrapOperation(
            method = "save(Lnet/minecraft/world/chunk/Chunk;)Z",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/world/ServerChunkLoadingManager;set(Lnet/minecraft/util/math/ChunkPos;Ljava/util/function/Supplier;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<Void> largerworld$queueChunkWrite(
            ServerChunkLoadingManager storage,
            ChunkPos pos,
            Supplier<NbtCompound> nbt,
            Operation<CompletableFuture<Void>> original) {
        ServerChunkLoadingManager manager = (ServerChunkLoadingManager) (Object) this;
        return CellChunkIoQueue.enqueue(
                manager, pos, nbt,
                selected -> storage.set(pos, selected));
    }

    @WrapOperation(
            method = "method_60440",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/world/ServerWorld;unloadEntities(Lnet/minecraft/world/chunk/WorldChunk;)V"))
    private void largerworld$waitForChunkWriteBeforeEntityUnload(
            ServerWorld serverWorld,
            WorldChunk chunk,
            Operation<Void> original) {
        ServerChunkLoadingManager manager = (ServerChunkLoadingManager) (Object) this;
        CompletableFuture<Void> barrier = CellChunkIoQueue.barrier(
                manager, chunk.getPos());
        if (barrier.isDone()) {
            original.call(serverWorld, chunk);
            return;
        }
        barrier.whenComplete((ignored, error) -> world.getServer().execute(
                () -> original.call(serverWorld, chunk)));
    }

    @WrapMethod(method = "save(Z)V")
    private void largerworld$flushQueuedWrites(
            boolean flush, Operation<Void> original) {
        original.call(flush);
        if (!flush) {
            return;
        }
        ServerChunkLoadingManager manager = (ServerChunkLoadingManager) (Object) this;
        CellChunkIoQueue.barrier(manager).join();
        manager.completeAll(true).join();
    }
}
