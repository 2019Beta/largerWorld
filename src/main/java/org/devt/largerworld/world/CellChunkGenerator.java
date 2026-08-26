package org.devt.largerworld.world;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.MapCodec;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.entry.RegistryEntryList;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructureTemplateManager;
import net.minecraft.util.collection.WeightedPool;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.ChunkRegion;
import net.minecraft.world.HeightLimitView;
import net.minecraft.world.Heightmap;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.GenerationSettings;
import net.minecraft.world.biome.SpawnSettings;
import net.minecraft.world.biome.source.BiomeAccess;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.Blender;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.chunk.VerticalBlockSample;
import net.minecraft.world.gen.chunk.placement.StructurePlacementCalculator;
import net.minecraft.world.gen.noise.NoiseConfig;
import net.minecraft.world.gen.structure.Structure;
import net.minecraft.entity.SpawnGroup;
import org.devt.largerworld.coordinate.CellPos;
import org.devt.largerworld.mixin.ChunkGeneratorAccessor;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Presents cell-local chunks to vanilla world generation at their continuous
 * global coordinates while leaving their storage and network identity local.
 */
public final class CellChunkGenerator extends ChunkGenerator {
    private final ChunkGenerator delegate;
    private final CellPos cell;

    public CellChunkGenerator(ChunkGenerator delegate, CellPos cell) {
        super(delegate.getBiomeSource(), delegate::getGenerationSettings);
        this.delegate = delegate;
        this.cell = cell;
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> getCodec() {
        return ((ChunkGeneratorAccessor) delegate).largerworld$invokeGetCodec();
    }

    @Override
    public StructurePlacementCalculator createStructurePlacementCalculator(
            RegistryWrapper<net.minecraft.structure.StructureSet> structureSets,
            NoiseConfig noiseConfig,
            long seed) {
        return delegate.createStructurePlacementCalculator(structureSets, noiseConfig, seed);
    }

    @Override
    public CompletableFuture<Chunk> populateBiomes(
            NoiseConfig noiseConfig, Blender blender, StructureAccessor structures, Chunk chunk) {
        return shiftedFuture(chunk, () -> delegate.populateBiomes(noiseConfig, blender, structures, chunk));
    }

    @Override
    public void carve(
            ChunkRegion region,
            long seed,
            NoiseConfig noiseConfig,
            BiomeAccess biomeAccess,
            StructureAccessor structures,
            Chunk chunk) {
        shifted(chunk, () -> delegate.carve(region, seed, noiseConfig, biomeAccess, structures, chunk));
    }

    @Override
    public void generateFeatures(StructureWorldAccess world, Chunk chunk, StructureAccessor structures) {
        shifted(chunk, () -> delegate.generateFeatures(world, chunk, structures));
    }

    @Override
    public void buildSurface(
            ChunkRegion region, StructureAccessor structures, NoiseConfig noiseConfig, Chunk chunk) {
        shifted(chunk, () -> delegate.buildSurface(region, structures, noiseConfig, chunk));
    }

    @Override
    public void populateEntities(ChunkRegion region) {
        delegate.populateEntities(region);
    }

    @Override
    public int getSpawnHeight(HeightLimitView world) {
        return delegate.getSpawnHeight(world);
    }

    @Override
    public int getWorldHeight() {
        return delegate.getWorldHeight();
    }

    @Override
    public WeightedPool<SpawnSettings.SpawnEntry> getEntitySpawnList(
            RegistryEntry<Biome> biome,
            StructureAccessor structures,
            SpawnGroup group,
            BlockPos pos) {
        return delegate.getEntitySpawnList(biome, structures, group, WorldgenCoordinates.toGlobalBlock(cell, pos));
    }

    @Override
    public void setStructureStarts(
            DynamicRegistryManager registryManager,
            StructurePlacementCalculator placementCalculator,
            StructureAccessor structures,
            Chunk chunk,
            StructureTemplateManager structureManager,
            RegistryKey<World> worldKey) {
        shifted(chunk, () -> delegate.setStructureStarts(
                registryManager, placementCalculator, structures, chunk, structureManager, worldKey));
    }

    @Override
    public void addStructureReferences(
            StructureWorldAccess world, StructureAccessor structures, Chunk chunk) {
        shifted(chunk, () -> delegate.addStructureReferences(world, structures, chunk));
    }

    @Override
    public CompletableFuture<Chunk> populateNoise(
            Blender blender, NoiseConfig noiseConfig, StructureAccessor structures, Chunk chunk) {
        return shiftedFuture(chunk, () -> delegate.populateNoise(blender, noiseConfig, structures, chunk));
    }

    @Override
    public int getSeaLevel() {
        return delegate.getSeaLevel();
    }

    @Override
    public int getMinimumY() {
        return delegate.getMinimumY();
    }

    @Override
    public int getHeight(
            int x, int z, Heightmap.Type heightmap, HeightLimitView world, NoiseConfig noiseConfig) {
        return delegate.getHeight(
                WorldgenCoordinates.toGlobalBlockX(cell, x),
                WorldgenCoordinates.toGlobalBlockZ(cell, z),
                heightmap,
                world,
                noiseConfig);
    }

    @Override
    public VerticalBlockSample getColumnSample(
            int x, int z, HeightLimitView world, NoiseConfig noiseConfig) {
        return delegate.getColumnSample(
                WorldgenCoordinates.toGlobalBlockX(cell, x),
                WorldgenCoordinates.toGlobalBlockZ(cell, z),
                world,
                noiseConfig);
    }

    @Override
    public void appendDebugHudText(List<String> text, NoiseConfig noiseConfig, BlockPos pos) {
        delegate.appendDebugHudText(text, noiseConfig, WorldgenCoordinates.toGlobalBlock(cell, pos));
    }

    @Override
    public GenerationSettings getGenerationSettings(RegistryEntry<Biome> biome) {
        return delegate.getGenerationSettings(biome);
    }

    @Override
    public Pair<BlockPos, RegistryEntry<Structure>> locateStructure(
            ServerWorld world,
            RegistryEntryList<Structure> structures,
            BlockPos center,
            int radius,
            boolean skipReferencedStructures) {
        Pair<BlockPos, RegistryEntry<Structure>> result = delegate.locateStructure(
                world,
                structures,
                WorldgenCoordinates.toGlobalBlock(cell, center),
                radius,
                skipReferencedStructures);
        return result == null ? null : Pair.of(
                WorldgenCoordinates.toLocalBlock(cell, result.getFirst()), result.getSecond());
    }

    private void shifted(Chunk chunk, Runnable action) {
        WorldgenCoordinates.begin(chunk, cell);
        try {
            action.run();
        } finally {
            WorldgenCoordinates.end(chunk);
        }
    }

    private CompletableFuture<Chunk> shiftedFuture(
            Chunk chunk, Supplier<CompletableFuture<Chunk>> action) {
        WorldgenCoordinates.begin(chunk, cell);
        try {
            return action.get().whenComplete((result, failure) -> WorldgenCoordinates.end(chunk));
        } catch (Throwable failure) {
            WorldgenCoordinates.end(chunk);
            throw failure;
        }
    }
}
