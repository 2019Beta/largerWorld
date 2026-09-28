package org.devt.largerworld.server;

import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import org.devt.largerworld.coordinate.CellPos;
import org.devt.largerworld.coordinate.VirtualPosition;
import org.devt.largerworld.world.CellWorldKey;
import org.devt.largerworld.world.CellWorldManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Keeps only the canonical neighboring chunks in player simulation range ticking. */
public final class CellSimulationTracker {
    private static final int TICKET_RADIUS = 2;
    private static final long CHUNKS_PER_CELL = VirtualPosition.CELL_SIZE / 16L;
    private static final int MIN_CANONICAL_CHUNK = (int) (-VirtualPosition.HALF_CELL / 16L);
    private static final int MAX_CANONICAL_CHUNK = (int) (VirtualPosition.HALF_CELL / 16L - 1L);

    private static final Map<MinecraftServer, ServerState> SERVERS = new IdentityHashMap<>();

    private CellSimulationTracker() {
    }

    /** Reconciles each player's projected simulation footprint once per server tick. */
    public static void tick(MinecraftServer server) {
        ServerState state = SERVERS.computeIfAbsent(server, ignored -> new ServerState());
        Set<UUID> activePlayers = new HashSet<>();
        int simulationDistance = Math.max(0, server.getPlayerManager().getSimulationDistance());

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (player.isRemoved()) {
                continue;
            }
            ServerWorld world = player.getEntityWorld();
            UUID playerId = player.getUuid();
            activePlayers.add(playerId);

            PositionSignature signature = new PositionSignature(
                    CellWorldKey.baseWorld(world.getRegistryKey()),
                    CellWorldKey.cell(world.getRegistryKey()),
                    MathHelper.floor(player.getX()) >> 4,
                    MathHelper.floor(player.getZ()) >> 4,
                    simulationDistance,
                    player.isSpectator());
            PlayerCoverage previous = state.players.get(playerId);
            if (previous == null || !previous.signature.equals(signature)) {
                Map<CellPos, Set<Long>> plan = signature.spectator()
                        ? Map.of()
                        : planCanonicalChunks(
                                signature.cell(), signature.chunkX(), signature.chunkZ(),
                                signature.simulationDistance());
                Map<CellPos, Set<TicketKey>> installed = resolvePlan(
                        server, signature.baseWorld(), plan);
                Set<TicketKey> oldTickets = previous == null
                        ? Set.of()
                        : flatten(previous.installedByCell);
                Set<TicketKey> newTickets = flatten(installed);
                replaceTickets(state, oldTickets, newTickets);
                state.players.put(playerId,
                        new PlayerCoverage(signature, plan, installed));
            } else {
                retryUnloadedCells(server, state, signature.baseWorld(), previous);
            }
        }

        for (UUID playerId : new ArrayList<>(state.players.keySet())) {
            if (activePlayers.contains(playerId)) {
                continue;
            }
            PlayerCoverage removed = state.players.remove(playerId);
            releaseTickets(state, flatten(removed.installedByCell));
        }

        if (state.players.isEmpty() && state.references.isEmpty()) {
            SERVERS.remove(server);
        }
    }

    /** Releases a disconnecting player's exact footprint immediately. */
    public static void forget(ServerPlayerEntity player) {
        MinecraftServer server = player.getEntityWorld().getServer();
        ServerState state = SERVERS.get(server);
        if (state == null) {
            return;
        }
        PlayerCoverage removed = state.players.remove(player.getUuid());
        if (removed != null) {
            releaseTickets(state, flatten(removed.installedByCell));
        }
        if (state.players.isEmpty() && state.references.isEmpty()) {
            SERVERS.remove(server);
        }
    }

    /** Whether explicit cross-cell simulation tickets still retain this world. */
    public static boolean isWorldInUse(ServerWorld world) {
        ServerState state = SERVERS.get(world.getServer());
        return state != null && state.worldReferences.containsKey(world);
    }

    /** Ticket state dies with the server; worlds themselves are closed by the server lifecycle. */
    public static void clearServerState(MinecraftServer server) {
        SERVERS.remove(server);
    }

    /**
     * Plans target-cell canonical chunk centers within the square simulation distance.
     * The source chunk plane is local to {@code currentCell}; output chunk longs are local
     * to each returned target cell.
     */
    static Map<CellPos, Set<Long>> planCanonicalChunks(
            CellPos currentCell, int playerChunkX, int playerChunkZ, int simulationDistance) {
        if (simulationDistance < 0) {
            throw new IllegalArgumentException("Simulation distance must be non-negative");
        }

        long minPlayerX = (long) playerChunkX - simulationDistance;
        long maxPlayerX = (long) playerChunkX + simulationDistance;
        long minPlayerZ = (long) playerChunkZ - simulationDistance;
        long maxPlayerZ = (long) playerChunkZ + simulationDistance;
        Map<CellPos, Set<Long>> result = new LinkedHashMap<>();

        for (int deltaZ = -1; deltaZ <= 1; deltaZ++) {
            for (int deltaX = -1; deltaX <= 1; deltaX++) {
                if (deltaX == 0 && deltaZ == 0) {
                    continue;
                }

                long cellChunkOffsetX = deltaX * CHUNKS_PER_CELL;
                long cellChunkOffsetZ = deltaZ * CHUNKS_PER_CELL;
                long firstX = Math.max(minPlayerX,
                        cellChunkOffsetX + MIN_CANONICAL_CHUNK);
                long lastX = Math.min(maxPlayerX,
                        cellChunkOffsetX + MAX_CANONICAL_CHUNK);
                long firstZ = Math.max(minPlayerZ,
                        cellChunkOffsetZ + MIN_CANONICAL_CHUNK);
                long lastZ = Math.min(maxPlayerZ,
                        cellChunkOffsetZ + MAX_CANONICAL_CHUNK);
                if (firstX > lastX || firstZ > lastZ) {
                    continue;
                }

                CellPos targetCell = currentCell.add(deltaX, deltaZ);
                Set<Long> chunks = result.computeIfAbsent(
                        targetCell, ignored -> new LinkedHashSet<>());
                for (long sourceChunkX = firstX; sourceChunkX <= lastX; sourceChunkX++) {
                    int localChunkX = Math.toIntExact(sourceChunkX - cellChunkOffsetX);
                    for (long sourceChunkZ = firstZ; sourceChunkZ <= lastZ; sourceChunkZ++) {
                        int localChunkZ = Math.toIntExact(sourceChunkZ - cellChunkOffsetZ);
                        chunks.add(ChunkPos.toLong(localChunkX, localChunkZ));
                    }
                }
            }
        }

        Map<CellPos, Set<Long>> immutable = new LinkedHashMap<>();
        result.forEach((cell, chunks) -> immutable.put(cell, Set.copyOf(chunks)));
        return Map.copyOf(immutable);
    }

    /** Adds one shared reference; returns true only when the underlying ticket is new. */
    static <K> boolean addReference(Map<K, Integer> references, K key) {
        int count = references.getOrDefault(key, 0);
        references.put(key, count + 1);
        return count == 0;
    }

    /** Removes one shared reference; returns true only when the underlying ticket is last. */
    static <K> boolean removeReference(Map<K, Integer> references, K key) {
        Integer count = references.get(key);
        if (count == null) {
            return false;
        }
        if (count <= 1) {
            references.remove(key);
            return true;
        }
        references.put(key, count - 1);
        return false;
    }

    private static Map<CellPos, Set<TicketKey>> resolvePlan(
            MinecraftServer server,
            RegistryKey<World> baseWorld,
            Map<CellPos, Set<Long>> plan) {
        Map<CellPos, Set<TicketKey>> installed = new LinkedHashMap<>();
        for (Map.Entry<CellPos, Set<Long>> entry : plan.entrySet()) {
            Set<TicketKey> tickets = resolveCell(server, baseWorld, entry.getKey(), entry.getValue());
            if (tickets != null) {
                installed.put(entry.getKey(), tickets);
            }
        }
        return installed;
    }

    private static Set<TicketKey> resolveCell(
            MinecraftServer server,
            RegistryKey<World> baseWorld,
            CellPos cell,
            Set<Long> chunkPositions) {
        ServerWorld world;
        try {
            world = CellWorldManager.getOrCreate(server, baseWorld, cell);
        } catch (CellWorldManager.CellCapacityException exception) {
            return null;
        }

        Set<TicketKey> tickets = new LinkedHashSet<>();
        for (long chunkPosition : chunkPositions) {
            tickets.add(new TicketKey(world, chunkPosition));
        }
        return Set.copyOf(tickets);
    }

    private static void retryUnloadedCells(
            MinecraftServer server,
            ServerState state,
            RegistryKey<World> baseWorld,
            PlayerCoverage coverage) {
        for (Map.Entry<CellPos, Set<Long>> entry : coverage.plannedByCell.entrySet()) {
            if (coverage.installedByCell.containsKey(entry.getKey())) {
                continue;
            }
            Set<TicketKey> tickets = resolveCell(
                    server, baseWorld, entry.getKey(), entry.getValue());
            if (tickets == null) {
                continue;
            }
            acquireTickets(state, tickets);
            coverage.installedByCell.put(entry.getKey(), tickets);
        }
    }

    private static void replaceTickets(
            ServerState state, Set<TicketKey> oldTickets, Set<TicketKey> newTickets) {
        Set<TicketKey> additions = new HashSet<>(newTickets);
        additions.removeAll(oldTickets);
        acquireTickets(state, additions);

        Set<TicketKey> removals = new HashSet<>(oldTickets);
        removals.removeAll(newTickets);
        releaseTickets(state, removals);
    }

    private static void acquireTickets(ServerState state, Set<TicketKey> tickets) {
        for (TicketKey key : tickets) {
            boolean firstReference = addReference(state.references, key);
            if (!firstReference) {
                continue;
            }
            key.world().getChunkManager().addTicket(
                    CellChunkTickets.SIMULATION,
                    new ChunkPos(key.chunkPosition()),
                    TICKET_RADIUS);
            state.worldReferences.merge(key.world(), 1, Integer::sum);
        }
    }

    private static void releaseTickets(ServerState state, Set<TicketKey> tickets) {
        for (TicketKey key : tickets) {
            boolean lastReference = removeReference(state.references, key);
            if (!lastReference) {
                continue;
            }
            key.world().getChunkManager().removeTicket(
                    CellChunkTickets.SIMULATION,
                    new ChunkPos(key.chunkPosition()),
                    TICKET_RADIUS);
            state.worldReferences.computeIfPresent(key.world(), (world, count) ->
                    count <= 1 ? null : count - 1);
        }
    }

    private static Set<TicketKey> flatten(Map<CellPos, Set<TicketKey>> byCell) {
        Set<TicketKey> result = new HashSet<>();
        byCell.values().forEach(result::addAll);
        return result;
    }

    private static final class ServerState {
        private final Map<UUID, PlayerCoverage> players = new HashMap<>();
        private final Map<TicketKey, Integer> references = new HashMap<>();
        private final Map<ServerWorld, Integer> worldReferences = new IdentityHashMap<>();
    }

    private static final class PlayerCoverage {
        private final PositionSignature signature;
        private final Map<CellPos, Set<Long>> plannedByCell;
        private final Map<CellPos, Set<TicketKey>> installedByCell;

        private PlayerCoverage(
                PositionSignature signature,
                Map<CellPos, Set<Long>> plannedByCell,
                Map<CellPos, Set<TicketKey>> installedByCell) {
            this.signature = signature;
            this.plannedByCell = plannedByCell;
            this.installedByCell = new HashMap<>(installedByCell);
        }
    }

    private record PositionSignature(
            RegistryKey<World> baseWorld,
            CellPos cell,
            int chunkX,
            int chunkZ,
            int simulationDistance,
            boolean spectator) {
    }

    private record TicketKey(ServerWorld world, long chunkPosition) {
    }
}
