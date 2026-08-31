package org.devt.largerworld.server;

import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkStatus;
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

    public static CellChunkTaskKey regionData(ServerWorld world, ChunkPos localPos) {
        return create(world, localPos, Target.REGION_DATA);
    }

    public static CellChunkTaskKey status(
            ServerWorld world, ChunkPos localPos, ChunkStatus status) {
        return create(world, localPos, Target.from(status));
    }

    private static CellChunkTaskKey create(
            ServerWorld world, ChunkPos localPos, Target target) {
        RegistryKey<World> worldKey = world.getRegistryKey();
        return new CellChunkTaskKey(
                CellWorldKey.baseWorld(worldKey),
                CellWorldKey.cell(worldKey),
                localPos.x,
                localPos.z,
                target);
    }

    /** Explicit nodes used by the Cell-aware chunk task graph. */
    public enum Target {
        REGION_DATA,
        EMPTY,
        STRUCTURE_STARTS,
        STRUCTURE_REFERENCES,
        BIOMES,
        NOISE,
        SURFACE,
        CARVERS,
        FEATURES,
        INITIALIZE_LIGHT,
        LIGHT,
        SPAWN,
        FULL,
        ACCESSIBLE;

        public static Target from(ChunkStatus status) {
            if (status == ChunkStatus.EMPTY) return EMPTY;
            if (status == ChunkStatus.STRUCTURE_STARTS) return STRUCTURE_STARTS;
            if (status == ChunkStatus.STRUCTURE_REFERENCES) return STRUCTURE_REFERENCES;
            if (status == ChunkStatus.BIOMES) return BIOMES;
            if (status == ChunkStatus.NOISE) return NOISE;
            if (status == ChunkStatus.SURFACE) return SURFACE;
            if (status == ChunkStatus.CARVERS) return CARVERS;
            if (status == ChunkStatus.FEATURES) return FEATURES;
            if (status == ChunkStatus.INITIALIZE_LIGHT) return INITIALIZE_LIGHT;
            if (status == ChunkStatus.LIGHT) return LIGHT;
            if (status == ChunkStatus.SPAWN) return SPAWN;
            if (status == ChunkStatus.FULL) return FULL;
            throw new IllegalArgumentException("Unsupported chunk status: " + status.getId());
        }
    }
}
