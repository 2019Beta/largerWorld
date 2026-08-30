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
import org.devt.largerworld.server.CellInteractionRouting;
import org.devt.largerworld.server.CellTickSchedulerRouting;
import org.devt.largerworld.server.CellViewTracker;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Creates and owns independent backing ServerWorld instances for coordinate cells. */
public final class CellWorldManager {
    private static final int EMPTY_WORLD_TTL_TICKS = 20 * 60;
    private static final CellCreationLimits CREATION_LIMITS = new CellCreationLimits(
            Integer.getInteger("largerworld.maxActiveCells", 256),
            Integer.getInteger("largerworld.maxCellCreationsPerTick", 16));
    private static final Map<MinecraftServer, Map<RegistryKey<World>, Integer>> IDLE_TICKS = new HashMap<>();
    private static final Map<MinecraftServer, CreationBudget> CREATION_BUDGETS = new HashMap<>();

    private CellWorldManager() {
    }

    public static ServerWorld getOrCreate(
            MinecraftServer server, RegistryKey<World> baseWorld, CellPos cell) {
        return getOrCreate(server, CellWorldKey.forCell(baseWorld, cell));
    }

    /** Returns a cell only when it is already active; behavior queries must not synchronously create worlds. */
    public static synchronized ServerWorld getIfLoaded(
            MinecraftServer server, RegistryKey<World> baseWorld, CellPos cell) {
        MinecraftServerAccessor accessor = (MinecraftServerAccessor) server;
        return accessor.largerworld$getWorldMap().get(CellWorldKey.forCell(baseWorld, cell));
    }

    public static synchronized ServerWorld getOrCreate(
            MinecraftServer server, RegistryKey<World> requestedKey) {
        MinecraftServerAccessor accessor = (MinecraftServerAccessor) server;
        Map<RegistryKey<World>, ServerWorld> worlds = accessor.largerworld$getWorldMap();
        ServerWorld existing = worlds.get(requestedKey);
        if (existing != null) {
            return existing;
        }

        enforceCreationLimits(server, worlds);

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

        WorldgenCoordinates.register(
                created.getChunkManager().getNoiseConfig(), parsed.cell(), baseWorld.getSeed());
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
            if (!world.getPlayers().isEmpty()
                    || CellViewTracker.isWorldWatched(world)
                    || CellInteractionRouting.isWorldInUse(world)) {
                idle.remove(key);
                continue;
            }

            int ticks = idle.merge(key, 1, Integer::sum);
            if (ticks < EMPTY_WORLD_TTL_TICKS) {
                continue;
            }

            try {
                var noiseConfig = world.getChunkManager().getNoiseConfig();
                world.save(null, true, false);
                world.close();
                worlds.remove(key);
                idle.remove(key);
                WorldgenCoordinates.unregister(noiseConfig);
                CellTickSchedulerRouting.unregisterWorld(world);
                Largerworld.LOGGER.info("Saved and unloaded empty coordinate cell {}", key.getValue());
            } catch (IOException exception) {
                // Keep the world reachable and retry later. Removing it first can
                // allow a second ServerWorld to open the same region files.
                idle.put(key, 0);
                Largerworld.LOGGER.error("Failed to save/close coordinate cell {}; keeping it loaded",
                        key.getValue(), exception);
            }
        }
    }

    public static synchronized void clearServerState(MinecraftServer server) {
        IDLE_TICKS.remove(server);
        CREATION_BUDGETS.remove(server);
    }

    public static int maxActiveCellWorlds() {
        return CREATION_LIMITS.maxActiveCells();
    }

    private static void enforceCreationLimits(
            MinecraftServer server, Map<RegistryKey<World>, ServerWorld> worlds) {
        long activeCells = worlds.keySet().stream().filter(key -> CellWorldKey.parse(key).isPresent()).count();
        long tick = server.getTicks();
        CreationBudget budget = CREATION_BUDGETS.computeIfAbsent(
                server, ignored -> new CreationBudget(tick, 0));
        if (budget.tick() != tick) {
            budget = new CreationBudget(tick, 0);
        }
        if (!CREATION_LIMITS.allows(Math.toIntExact(activeCells), budget.created())) {
            CREATION_BUDGETS.put(server, budget);
            throw new CellCapacityException(CREATION_LIMITS.rejectionReason(
                    Math.toIntExact(activeCells), budget.created()));
        }
        CREATION_BUDGETS.put(server, new CreationBudget(tick, budget.created() + 1));
    }

    public static final class CellCapacityException extends IllegalStateException {
        public CellCapacityException(String message) {
            super(message);
        }
    }

    private record CreationBudget(long tick, int created) {
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
