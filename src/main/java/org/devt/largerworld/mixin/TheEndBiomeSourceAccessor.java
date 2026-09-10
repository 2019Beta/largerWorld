package org.devt.largerworld.mixin;

import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.TheEndBiomeSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(TheEndBiomeSource.class)
public interface TheEndBiomeSourceAccessor {
    @Accessor("highlandsBiome") RegistryEntry<Biome> largerworld$highlands();
    @Accessor("midlandsBiome") RegistryEntry<Biome> largerworld$midlands();
    @Accessor("smallIslandsBiome") RegistryEntry<Biome> largerworld$smallIslands();
    @Accessor("barrensBiome") RegistryEntry<Biome> largerworld$barrens();
}
