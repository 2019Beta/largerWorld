package org.devt.largerworld.world;

import com.mojang.serialization.MapCodec;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.biome.source.util.MultiNoiseUtil;
import org.devt.largerworld.coordinate.CellPos;
import org.devt.largerworld.coordinate.VirtualPosition;
import org.devt.largerworld.mixin.BiomeSourceAccessor;

import java.util.stream.Stream;

/** Samples the canonical biome source at this cell's global quart coordinates. */
public final class CellBiomeSource extends BiomeSource {
    private static final long CELL_SIZE_BIOMES = VirtualPosition.CELL_SIZE / 4L;

    private final BiomeSource delegate;
    private final CellPos cell;

    public CellBiomeSource(BiomeSource delegate, CellPos cell) {
        this.delegate = delegate;
        this.cell = cell;
    }

    public CellPos cell() {
        return cell;
    }

    @Override
    protected MapCodec<? extends BiomeSource> getCodec() {
        return ((BiomeSourceAccessor) delegate).largerworld$invokeGetCodec();
    }

    @Override
    protected Stream<RegistryEntry<Biome>> biomeStream() {
        return delegate.getBiomes().stream();
    }

    @Override
    public RegistryEntry<Biome> getBiome(
            int biomeX, int biomeY, int biomeZ, MultiNoiseUtil.MultiNoiseSampler noise) {
        return delegate.getBiome(
                offset(biomeX, cell.x()),
                biomeY,
                offset(biomeZ, cell.z()),
                noise);
    }

    private static int offset(int local, long cellCoordinate) {
        // BiomeSource is int-based just like noise generation. Use the same
        // deterministic low-32-bit folding instead of failing at distant cells.
        return (int) (local + cellCoordinate * CELL_SIZE_BIOMES);
    }
}
