package org.devt.largerworld.mixin.client;

import net.minecraft.client.world.ClientChunkManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;
import org.devt.largerworld.client.network.ClientChunkSnapshot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.ArrayList;
import java.util.List;

@Mixin(ClientChunkManager.class)
public abstract class ClientChunkManagerMixin implements ClientChunkSnapshot {
    @Unique private int largerworld$distance;
    @Unique private int largerworld$centerX;
    @Unique private int largerworld$centerZ;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void largerworld$initialDistance(ClientWorld world, int distance, CallbackInfo ci) {
        largerworld$distance = distance;
    }

    @Inject(method = "updateLoadDistance", at = @At("HEAD"))
    private void largerworld$rememberDistance(int distance, CallbackInfo ci) {
        largerworld$distance = distance;
    }

    @Inject(method = "setChunkMapCenter", at = @At("HEAD"))
    private void largerworld$rememberCenter(int x, int z, CallbackInfo ci) {
        largerworld$centerX = x;
        largerworld$centerZ = z;
    }

    @Override
    public List<WorldChunk> largerworld$loadedChunks() {
        ClientChunkManager manager = (ClientChunkManager) (Object) this;
        int radius = Math.max(2, largerworld$distance) + 3;
        List<WorldChunk> chunks = new ArrayList<>();
        for (int z = largerworld$centerZ - radius; z <= largerworld$centerZ + radius; z++) {
            for (int x = largerworld$centerX - radius; x <= largerworld$centerX + radius; x++) {
                WorldChunk chunk = manager.getChunk(x, z, ChunkStatus.FULL, false);
                if (chunk != null) {
                    chunks.add(chunk);
                }
            }
        }
        return chunks;
    }

    @Override public ChunkPos largerworld$mapCenter() {
        return new ChunkPos(largerworld$centerX, largerworld$centerZ);
    }

    @Override public int largerworld$loadDistance() {
        return largerworld$distance;
    }
}
