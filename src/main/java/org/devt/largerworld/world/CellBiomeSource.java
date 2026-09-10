package org.devt.largerworld.world;

import com.mojang.serialization.MapCodec;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.biome.source.TheEndBiomeSource;
import net.minecraft.world.biome.source.util.MultiNoiseUtil;
import net.minecraft.world.gen.densityfunction.DensityFunction;
import org.devt.largerworld.coordinate.CellPos;
import org.devt.largerworld.mixin.BiomeSourceAccessor;
import org.devt.largerworld.mixin.TheEndBiomeSourceAccessor;

import java.util.stream.Stream;

/** Samples the canonical biome source at this cell's global quart coordinates. */
public final class CellBiomeSource extends BiomeSource {

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
        if (delegate instanceof TheEndBiomeSource && !cell.equals(CellPos.ZERO)) {
            // Only the actual origin cell may contain the central End biome.
            // Folded ints near zero in a remote cell must not create it again.
            int blockX = WorldgenCoordinates.toGlobalBlockX(cell, biomeX << 2);
            int blockZ = WorldgenCoordinates.toGlobalBlockZ(cell, biomeZ << 2);
            int sampleX = (blockX & ~15) + 8;
            int sampleZ = (blockZ & ~15) + 8;
            double erosion = noise.erosion().sample(
                    new DensityFunction.UnblendedNoisePos(sampleX, biomeY << 2, sampleZ));
            TheEndBiomeSourceAccessor end = (TheEndBiomeSourceAccessor) delegate;
            if (erosion > 0.25) {
                return end.largerworld$highlands();
            }
            if (erosion >= -0.0625) {
                return end.largerworld$midlands();
            }
            return erosion < -0.21875 ? end.largerworld$smallIslands() : end.largerworld$barrens();
        }
        return delegate.getBiome(
                WorldgenCoordinates.toGlobalBiomeX(cell, biomeX),
                biomeY,
                WorldgenCoordinates.toGlobalBiomeZ(cell, biomeZ),
                noise);
    }
}
