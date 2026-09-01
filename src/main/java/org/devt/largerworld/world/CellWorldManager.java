package org.devt.largerworld.world;

import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.WanderingTraderManager;
import net.minecraft.world.World;
import net.minecraft.world.PersistentStateManager;
import net.minecraft.world.border.WorldBorder;
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
import org.devt.largerworld.mixin.ServerWorldWeatherAccessor;
import org.devt.largerworld.server.CellInteractionRouting;
import org.devt.largerworld.server.CellChunkIoQueue;
import org.devt.largerworld.server.CellTickSchedulerRouting;
import org.devt.largerworld.server.CellViewTracker;
import org.devt.largerworld.server.CellWorldEnvironmentSync;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Creates and owns independent backing ServerWorld instances for coordinate cells. */
public final class CellWorldManager {
    private static final int EMPTY_WORLD_TTL_TICKS = 20 * 60;
    private static final CellCreationLimits CREATION_LIMITS = new CellCreationLimits(
            Integer.getInteger("largerworld.maxActiveCells", 256),
            Integer.getInteger("largerworld.maxCellCreationsPerTick", 16));
    private static final Map<MinecraftServer, Map<RegistryKey<World>, Integer>> IDLE_TICKS = new HashMap<>();
    private static final Map<MinecraftServer, CreationBudget> CREATION_BUDGETS = new HashMap<>();
    /**
     * Cell worlds whose durable writes are draining before final close.
     *
     * <p>Like Moonrise's staged holder unload, expensive persistence is kept
     * outside the final owner-thread removal step. A Cell remains present in
     * the server world map until the future completes, so it can be revived
     * without opening a second set of RegionFiles.</p>
     */
    private static final Map<MinecraftServer, Map<RegistryKey<World>, ClosingCell>> CLOSING_CELLS =
            new HashMap<>();

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
            revive(server, requestedKey);
            return existing;
        }

        enforceCreationLimits(server, worlds);

        CellWorldKey.Parsed parsed = CellWorldKey.parse(requestedKey)
                .orElseThrow(() -> new IllegalArgumentException("Not a Larger World cell key: " + requestedKey));
        ServerWorld baseWorld = worlds.get(parsed.baseWorld());
        if (baseWorld == null) {
            throw new IllegalStateException("Base dimension is not loaded: " + parsed.baseWorld().getValue());
        }

        CellWorldProperties properties = new CellWorldProperties(new UnmodifiableLevelProperties(
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
        PersistentStateManager stateManager = created.getPersistentStateManager();
        properties.attach(stateManager);
        WorldBorder border = stateManager.get(WorldBorder.TYPE);
        if (border == null) {
            WorldBorder.Properties initial = properties.initialWorldBorder()
                    .orElse(WorldBorder.Properties.DEFAULT);
            double coordinateScale = created.getDimension().coordinateScale();
            border = new WorldBorder(new WorldBorder.Properties(
                    initial.centerX() / coordinateScale,
                    initial.centerZ() / coordinateScale,
                    initial.damagePerBlock(),
                    initial.safeZone(),
                    initial.warningBlocks(),
                    initial.warningTime(),
                    initial.size(),
                    initial.lerpTime(),
                    initial.lerpTarget()));
            stateManager.set(WorldBorder.TYPE, border);
        }
        border.ensureInitialized(properties.getTime());
        border.setMaxRadius(server.getMaxWorldBorderRadius());
        properties.bindWorldBorder(border);
        ((ServerWorldWeatherAccessor) created).largerworld$initWeatherGradients();
        CellWorldEnvironmentSync.registerBorderListener(created);
        worlds.put(requestedKey, created);
        Largerworld.LOGGER.info("Loaded coordinate cell {} {} for base dimension {}",
                parsed.cell().x(), parsed.cell().z(), parsed.baseWorld().getValue());
        return created;
    }

    public static synchronized void tickEviction(MinecraftServer server) {
        MinecraftServerAccessor accessor = (MinecraftServerAccessor) server;
        Map<RegistryKey<World>, ServerWorld> worlds = accessor.largerworld$getWorldMap();
        Map<RegistryKey<World>, Integer> idle = IDLE_TICKS.computeIfAbsent(server, ignored -> new HashMap<>());
        Map<RegistryKey<World>, ClosingCell> closing = CLOSING_CELLS.computeIfAbsent(
                server, ignored -> new HashMap<>());

        for (ServerWorld world : new ArrayList<>(worlds.values())) {
            RegistryKey<World> key = world.getRegistryKey();
            if (CellWorldKey.parse(key).isEmpty()) {
                continue;
            }
            boolean active = isExternallyActive(world);
            ClosingCell closingCell = closing.get(key);
            if (active) {
                idle.remove(key);
                if (closingCell != null) {
                    closing.remove(key);
                    Largerworld.LOGGER.info(
                            "Cancelled close of coordinate cell {} because it became active",
                            key.getValue());
                }
                continue;
            }

            if (closingCell != null) {
                finishCloseIfReady(worlds, idle, closing, key, closingCell);
                continue;
            }

            int ticks = idle.merge(key, 1, Integer::sum);
            if (ticks < EMPTY_WORLD_TTL_TICKS) {
                continue;
            }

            // Moonrise does not begin holder unload until tickets, generation,
            // lighting and pending unload work report that the holder is safe.
            // Preserve that invariant at Cell granularity before snapshotting
            // the world's persistent state.
            if (world.getChunkManager().chunkLoadingManager.shouldDelayShutdown()) {
                continue;
            }

            try {
                var noiseConfig = world.getChunkManager().getNoiseConfig();
                var manager = world.getChunkManager().chunkLoadingManager;
                // `flush=false` still snapshots and queues dirty world/chunk
                // state but does not synchronously wait for StorageIoWorker.
                world.save(null, false, false);
                CompletableFuture<Void> durable = CellChunkIoQueue.barrier(manager)
                        .thenCompose(ignored -> manager.completeAll(false));
                closing.put(key, new ClosingCell(world, noiseConfig, durable));
                idle.remove(key);
                Largerworld.LOGGER.info(
                        "Draining durable writes before closing coordinate cell {}",
                        key.getValue());
            // In the target Minecraft version ServerWorld#save no longer
            // declares IOException, but it may still fail at runtime while
            // initiating persistence.
            } catch (RuntimeException exception) {
                idle.put(key, 0);
                Largerworld.LOGGER.error("Failed to begin saving coordinate cell {}; keeping it loaded",
                        key.getValue(), exception);
            }
        }

        if (closing.isEmpty()) {
            CLOSING_CELLS.remove(server);
        }
    }

    public static synchronized void clearServerState(MinecraftServer server) {
        IDLE_TICKS.remove(server);
        CREATION_BUDGETS.remove(server);
        CLOSING_CELLS.remove(server);
    }

    /** Returns whether the Cell is between save admission and final close. */
    public static synchronized boolean isClosing(ServerWorld world) {
        Map<RegistryKey<World>, ClosingCell> closing = CLOSING_CELLS.get(world.getServer());
        return closing != null && closing.containsKey(world.getRegistryKey());
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

    private static boolean isExternallyActive(ServerWorld world) {
        return !world.getPlayers().isEmpty()
                || CellViewTracker.isWorldWatched(world)
                || CellInteractionRouting.isWorldInUse(world);
    }

    private static void finishCloseIfReady(
            Map<RegistryKey<World>, ServerWorld> worlds,
            Map<RegistryKey<World>, Integer> idle,
            Map<RegistryKey<World>, ClosingCell> closing,
            RegistryKey<World> key,
            ClosingCell closingCell) {
        if (!closingCell.durableWrites().isDone()) {
            return;
        }
        try {
            // isDone() makes join non-blocking and gives us the original cause.
            closingCell.durableWrites().join();
        } catch (CompletionException exception) {
            closing.remove(key);
            idle.put(key, 0);
            Largerworld.LOGGER.error(
                    "Failed to drain writes for coordinate cell {}; keeping it loaded",
                    key.getValue(), exception.getCause());
            return;
        }

        ServerWorld current = worlds.get(key);
        if (current != closingCell.world() || isExternallyActive(closingCell.world())) {
            closing.remove(key);
            idle.remove(key);
            return;
        }

        try {
            // The expensive writes have completed. Final cache closure remains
            // on the server thread, matching Moonrise's owner-thread rule.
            closingCell.world().close();
            worlds.remove(key, closingCell.world());
            closing.remove(key);
            idle.remove(key);
            WorldgenCoordinates.unregister(closingCell.noiseConfig());
            CellTickSchedulerRouting.unregisterWorld(closingCell.world());
            Largerworld.LOGGER.info("Saved and unloaded empty coordinate cell {}", key.getValue());
        } catch (IOException exception) {
            closing.remove(key);
            idle.put(key, 0);
            Largerworld.LOGGER.error(
                    "Failed to close coordinate cell {}; keeping it loaded",
                    key.getValue(), exception);
        }
    }

    private static void revive(MinecraftServer server, RegistryKey<World> key) {
        Map<RegistryKey<World>, Integer> idle = IDLE_TICKS.get(server);
        if (idle != null) {
            idle.remove(key);
        }
        Map<RegistryKey<World>, ClosingCell> closing = CLOSING_CELLS.get(server);
        if (closing != null && closing.remove(key) != null) {
            Largerworld.LOGGER.info("Revived coordinate cell {} during asynchronous close", key.getValue());
            if (closing.isEmpty()) {
                CLOSING_CELLS.remove(server);
            }
        }
    }

    public static final class CellCapacityException extends IllegalStateException {
        public CellCapacityException(String message) {
            super(message);
        }
    }

    private record CreationBudget(long tick, int created) {
    }

    private record ClosingCell(
            ServerWorld world,
            net.minecraft.world.gen.noise.NoiseConfig noiseConfig,
            CompletableFuture<Void> durableWrites) {
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
