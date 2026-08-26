package org.devt.largerworld.world;

import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.WanderingTraderManager;
import net.minecraft.world.World;
import net.minecraft.world.biome.source.BiomeAccess;
import net.minecraft.world.dimension.DimensionOptions;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;
import net.minecraft.world.level.ServerWorldProperties;
import net.minecraft.world.level.UnmodifiableLevelProperties;
import net.minecraft.world.spawner.CatSpawner;
import net.minecraft.world.spawner.PatrolSpawner;
import net.minecraft.world.spawner.PhantomSpawner;
import net.minecraft.world.spawner.SpecialSpawner;
import net.minecraft.village.ZombieSiegeManager;
import org.devt.largerworld.Largerworld;
import org.devt.largerworld.coordinate.CellPos;
import org.devt.largerworld.mixin.MinecraftServerAccessor;
import org.devt.largerworld.server.CellViewTracker;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Creates and owns independent backing ServerWorld instances for coordinate cells. */
public final class CellWorldManager {
    private static final int EMPTY_WORLD_TTL_TICKS = 20 * 60;
    private static final Map<MinecraftServer, Map<RegistryKey<World>, Integer>> IDLE_TICKS = new HashMap<>();

    private CellWorldManager() {
    }

    public static ServerWorld getOrCreate(
            MinecraftServer server, RegistryKey<World> baseWorld, CellPos cell) {
        return getOrCreate(server, CellWorldKey.forCell(baseWorld, cell));
    }

    public static synchronized ServerWorld getOrCreate(
            MinecraftServer server, RegistryKey<World> requestedKey) {
        MinecraftServerAccessor accessor = (MinecraftServerAccessor) server;
        Map<RegistryKey<World>, ServerWorld> worlds = accessor.largerworld$getWorldMap();
        ServerWorld existing = worlds.get(requestedKey);
        if (existing != null) {
            return existing;
        }

        CellWorldKey.Parsed parsed = CellWorldKey.parse(requestedKey)
                .orElseThrow(() -> new IllegalArgumentException("Not a Larger World cell key: " + requestedKey));
        ServerWorld baseWorld = worlds.get(parsed.baseWorld());
        if (baseWorld == null) {
            throw new IllegalStateException("Base dimension is not loaded: " + parsed.baseWorld().getValue());
        }

        ServerWorldProperties properties = new CellWorldProperties(new UnmodifiableLevelProperties(
                server.getSaveProperties(), server.getSaveProperties().getMainWorldProperties()));
        DimensionOptions options = new DimensionOptions(
                baseWorld.getDimensionEntry(),
                createGenerator(baseWorld.getChunkManager().getChunkGenerator(), parsed.cell()));

        // Only the canonical base world advances shared time. Cell worlds still
        // receive the resulting time/weather through UnmodifiableLevelProperties.
        ServerWorld created = new ServerWorld(
                server,
                accessor.largerworld$getWorkerExecutor(),
                accessor.largerworld$getSession(),
                properties,
                requestedKey,
                options,
                server.getSaveProperties().isDebugWorld(),
                BiomeAccess.hashSeed(baseWorld.getSeed()),
                createSpawners(parsed.baseWorld(), properties),
                false,
                baseWorld.getRandomSequences());

        WorldgenCoordinates.register(created.getChunkManager().getNoiseConfig(), parsed.cell());
        created.getWorldBorder().setMaxRadius(server.getMaxWorldBorderRadius());
        worlds.put(requestedKey, created);
        Largerworld.LOGGER.info("Loaded coordinate cell {} {} for base dimension {}",
                parsed.cell().x(), parsed.cell().z(), parsed.baseWorld().getValue());
        return created;
    }

    public static synchronized void tickEviction(MinecraftServer server) {
        MinecraftServerAccessor accessor = (MinecraftServerAccessor) server;
        Map<RegistryKey<World>, ServerWorld> worlds = accessor.largerworld$getWorldMap();
        Map<RegistryKey<World>, Integer> idle = IDLE_TICKS.computeIfAbsent(server, ignored -> new HashMap<>());

        for (ServerWorld world : new ArrayList<>(worlds.values())) {
            RegistryKey<World> key = world.getRegistryKey();
            if (CellWorldKey.parse(key).isEmpty()) {
                continue;
            }
            if (!world.getPlayers().isEmpty() || CellViewTracker.isWorldWatched(world)) {
                idle.remove(key);
                continue;
            }

            int ticks = idle.merge(key, 1, Integer::sum);
            if (ticks < EMPTY_WORLD_TTL_TICKS) {
                continue;
            }

            idle.remove(key);
            worlds.remove(key);
            try {
                world.save(null, true, false);
                world.close();
                Largerworld.LOGGER.info("Saved and unloaded empty coordinate cell {}", key.getValue());
            } catch (IOException exception) {
                Largerworld.LOGGER.error("Failed to close coordinate cell {}", key.getValue(), exception);
            }
        }
    }

    private static List<SpecialSpawner> createSpawners(
            RegistryKey<World> baseWorld, ServerWorldProperties properties) {
        if (!baseWorld.equals(World.OVERWORLD)) {
            return List.of();
        }
        return List.of(
                new PhantomSpawner(),
                new PatrolSpawner(),
                new CatSpawner(),
                new ZombieSiegeManager(),
                new WanderingTraderManager(properties));
    }

    private static ChunkGenerator createGenerator(ChunkGenerator baseGenerator, CellPos cell) {
        if (baseGenerator instanceof NoiseChunkGenerator noiseGenerator) {
            return new NoiseChunkGenerator(
                    new CellBiomeSource(noiseGenerator.getBiomeSource(), cell),
                    noiseGenerator.getSettings());
        }
        return baseGenerator;
    }
}
