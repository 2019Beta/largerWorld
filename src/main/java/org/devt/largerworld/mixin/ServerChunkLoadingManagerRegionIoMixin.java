package org.devt.largerworld.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerChunkLoadingManager;
import net.minecraft.util.math.ChunkPos;
import org.devt.largerworld.server.CellRegionIoPrefetch;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Reuses a prediction-started StorageIoWorker read in the vanilla load path. */
@Mixin(ServerChunkLoadingManager.class)
public abstract class ServerChunkLoadingManagerRegionIoMixin {
    @WrapOperation(
            method = "getUpdatedChunkNbt",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/world/ServerChunkLoadingManager;getNbt(Lnet/minecraft/util/math/ChunkPos;)Ljava/util/concurrent/CompletableFuture;"),
            require = 0)
    private CompletableFuture<Optional<NbtCompound>> largerworld$reusePrefetchedRegionData(
            ServerChunkLoadingManager manager,
            ChunkPos pos,
            Operation<CompletableFuture<Optional<NbtCompound>>> original) {
        return CellRegionIoPrefetch.consumeOrRead(
                manager, pos, () -> original.call(manager, pos));
    }
}
