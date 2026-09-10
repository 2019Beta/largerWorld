package org.devt.largerworld.client.network;

import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;
import java.util.List;

public interface ClientChunkSnapshot {
    List<WorldChunk> largerworld$loadedChunks();
    ChunkPos largerworld$mapCenter();
    int largerworld$loadDistance();
}
