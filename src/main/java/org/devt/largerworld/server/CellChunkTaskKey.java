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
        return create(world, localPos, Target.ACCESSIBLE);
    }

    private static CellChunkTaskKey create(
            ServerWorld world, ChunkPos localPos, Target target) {
        RegistryKey<World> worldKey = world.getRegistryKey();
        // Vanilla's loader can briefly expose a boundary chunk in the backing
        // world's coordinate frame (for example +32768 when the canonical
        // range ends at +32767).  Task identities must still use the unique
        // canonical cell/local representation, otherwise the guard in the
        // record constructor turns a normal seam load into a worker crash.
        VirtualChunkPos canonical = VirtualChunkPos.fromClient(
                CellWorldKey.cell(worldKey), localPos.x, localPos.z);
        return new CellChunkTaskKey(
                CellWorldKey.baseWorld(worldKey),
                canonical.cell(),
                canonical.localX(),
                canonical.localZ(),
                target);
    }

    /** The only Cell-level request; vanilla owns all internal status nodes. */
    public enum Target {
        ACCESSIBLE
    }
}
