package org.devt.largerworld.server;

import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;
import org.devt.largerworld.coordinate.CellPos;

import java.util.Objects;

/** One globally identified chunk that may be mutated by a generation node. */
record CellChunkWriteKey(
        RegistryKey<World> dimension,
        CellPos cell,
        int localChunkX,
        int localChunkZ) {
    CellChunkWriteKey {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(cell, "cell");
    }

}
