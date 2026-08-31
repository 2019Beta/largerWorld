package org.devt.largerworld.server;

import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import org.devt.largerworld.coordinate.CellPos;
import org.devt.largerworld.coordinate.VirtualChunkPos;
import org.devt.largerworld.world.CellWorldKey;

import java.util.Objects;

/**
 * Globally unique identity for a chunk-engine task.
 *
 * <p>A vanilla {@link ChunkPos} is only unique inside one backing cell world.
 * Including the base dimension and cell prevents schedulers, caches and locks
 * from accidentally merging equal local positions from different cells.</p>
 */
public record CellChunkTaskKey(
        RegistryKey<World> dimension,
        CellPos cell,
        int localChunkX,
        int localChunkZ,
        Target target) {

    public CellChunkTaskKey {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(cell, "cell");
        Objects.requireNonNull(target, "target");
        if (!VirtualChunkPos.isCanonical(localChunkX, localChunkZ)) {
            throw new IllegalArgumentException("Chunk task coordinates must be canonical");
        }
    }

    public static CellChunkTaskKey accessible(ServerWorld world, ChunkPos localPos) {
        RegistryKey<World> worldKey = world.getRegistryKey();
        return new CellChunkTaskKey(
                CellWorldKey.baseWorld(worldKey),
                CellWorldKey.cell(worldKey),
                localPos.x,
                localPos.z,
                Target.ACCESSIBLE);
    }

    /** Chunk pipeline target; later backends can add explicit worldgen statuses. */
    public enum Target {
        ACCESSIBLE
    }
}
