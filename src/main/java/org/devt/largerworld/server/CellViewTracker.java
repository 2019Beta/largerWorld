package org.devt.largerworld.server;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.UnloadChunkS2CPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.network.ChunkFilter;
import net.minecraft.server.world.ServerChunkLoadingManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.chunk.WorldChunk;
import org.devt.largerworld.coordinate.CellPos;
import org.devt.largerworld.coordinate.VirtualChunkPos;
import org.devt.largerworld.mixin.ServerChunkLoadingManagerAccessor;
import org.devt.largerworld.world.CellWorldKey;
import org.devt.largerworld.world.CellWorldManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Maintains the part of a player's vanilla view cylinder that falls in
 * neighboring cells. Vanilla remains responsible for the current cell.
 */
public final class CellViewTracker {
    private static final Map<UUID, PlayerState> STATES = new HashMap<>();

    private CellViewTracker() {
    }

    public static void tick(MinecraftServer server) {
        Set<UUID> online = new HashSet<>();
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            online.add(player.getUuid());
            updatePlayer(server, player);
        }

        for (UUID uuid : new ArrayList<>(STATES.keySet())) {
            if (!online.contains(uuid)) {
                PlayerState state = STATES.remove(uuid);
                if (state != null) {
                    state.releaseAll();
                }
            }
        }
    }

    private static void updatePlayer(MinecraftServer server, ServerPlayerEntity player) {
        PlayerState state = STATES.computeIfAbsent(player.getUuid(), ignored -> new PlayerState(player));
        state.player = player;
        CellPos currentCell = CellWorldKey.cell(player.getEntityWorld().getRegistryKey());
        int centerX = MathHelper.floor(player.getX()) >> 4;
        int centerZ = MathHelper.floor(player.getZ()) >> 4;
        int radius = Math.max(2, player.getViewDistance());
        Set<VirtualChunkPos> desired = desiredShadowChunks(currentCell, centerX, centerZ, radius);

        state.updateWatches(server, desired);
        state.updateEntities();
        state.tickHandoff();
    }

    private static Set<VirtualChunkPos> desiredShadowChunks(
            CellPos currentCell, int centerX, int centerZ, int radius) {
        Set<VirtualChunkPos> desired = new HashSet<>();

        for (int dz = -radius - 1; dz <= radius + 1; dz++) {
            for (int dx = -radius - 1; dx <= radius + 1; dx++) {
                if (!ChunkFilter.isWithinDistance(
                        centerX, centerZ, radius, centerX + dx, centerZ + dz, true)) {
                    continue;
                }
                VirtualChunkPos virtual = VirtualChunkPos.fromClient(currentCell, centerX + dx, centerZ + dz);
                if (!virtual.cell().equals(currentCell)) {
                    desired.add(virtual);
                }
            }
        }
        return desired;
    }

    /**
     * Claims the source-cell part of the post-crossing view before vanilla
     * removes the player from that world. This makes entity listener ownership
     * overlap across the handoff instead of producing destroy/spawn packets.
     */
    public static void prepareTransition(
            MinecraftServer server,
            ServerPlayerEntity player,
            CellPos targetCell,
            double targetX,
            double targetZ) {
        PlayerState state = STATES.computeIfAbsent(player.getUuid(), ignored -> new PlayerState(player));
        state.player = player;
        CellPos sourceCell = CellWorldKey.cell(player.getEntityWorld().getRegistryKey());
        int centerX = MathHelper.floor(targetX) >> 4;
        int centerZ = MathHelper.floor(targetZ) >> 4;
        int radius = Math.max(2, player.getViewDistance());
        for (VirtualChunkPos pos : desiredShadowChunks(targetCell, centerX, centerZ, radius)) {
            if (pos.cell().equals(sourceCell)) {
                state.claimVanillaChunk(server, pos);
            }
        }
        state.updateEntities();
        state.beginHandoff(targetCell);
    }

    public static boolean shouldSuppressHandoffChunk(
            ServerPlayerEntity player, CellPos source, ChunkPos localPos) {
        PlayerState state = STATES.get(player.getUuid());
        return state != null && state.consumeHandoffChunk(
                new VirtualChunkPos(source, localPos.x, localPos.z));
    }

    public static boolean shouldHoldCurrentCellEntity(ServerPlayerEntity player, Entity entity) {
        PlayerState state = STATES.get(player.getUuid());
        if (state == null || state.handoffTicks <= 0
                || entity.getEntityWorld() != player.getEntityWorld()) {
            return false;
        }
        return shouldRetain(
                player,
                CellWorldKey.cell(entity.getEntityWorld().getRegistryKey()),
                entity.getChunkPos());
    }

    public static boolean shouldRetain(ServerPlayerEntity player, CellPos source, ChunkPos localPos) {
        if (!VirtualChunkPos.isCanonical(localPos.x, localPos.z)) {
            return false;
        }
        PlayerState state = STATES.get(player.getUuid());
        if (state == null) {
            return false;
        }
        CellPos current = CellWorldKey.cell(player.getEntityWorld().getRegistryKey());
        int centerX = MathHelper.floor(player.getX()) >> 4;
        int centerZ = MathHelper.floor(player.getZ()) >> 4;
        VirtualChunkPos virtual = new VirtualChunkPos(source, localPos.x, localPos.z);
        int x = virtual.clientX(current);
        int z = virtual.clientZ(current);
        int radius = Math.max(2, player.getViewDistance());
        return ChunkFilter.isWithinDistance(centerX, centerZ, radius, x, z, true);
    }

    public static List<ServerPlayerEntity> watchers(ServerWorld world, ChunkPos pos) {
        if (!VirtualChunkPos.isCanonical(pos.x, pos.z)) {
            return List.of();
        }
        CellPos cell = CellWorldKey.cell(world.getRegistryKey());
        VirtualChunkPos key = new VirtualChunkPos(cell, pos.x, pos.z);
        List<ServerPlayerEntity> result = new ArrayList<>();
        for (PlayerState state : STATES.values()) {
            Watch watch = state.watches.get(key);
            if (watch != null && watch.sent && state.player.networkHandler.isConnectionOpen()) {
                result.add(state.player);
            }
        }
        return result;
    }

    public static Entity findVisibleEntity(ServerPlayerEntity player, PlayerInteractEntityC2SPacket packet) {
        PlayerState state = STATES.get(player.getUuid());
        if (state == null) {
            return null;
        }
        Set<ServerWorld> checked = new HashSet<>();
        for (Watch watch : state.watches.values()) {
            if (!watch.sent || !checked.add(watch.world)) {
                continue;
            }
            Entity entity = packet.getEntity(watch.world);
            if (entity != null && VirtualChunkPos.isCanonical(
                    entity.getChunkPos().x, entity.getChunkPos().z)
                    && state.watches.containsKey(
                    new VirtualChunkPos(CellWorldKey.cell(watch.world.getRegistryKey()),
                            entity.getChunkPos().x, entity.getChunkPos().z))) {
                return entity;
            }
        }
        return null;
    }

    public static boolean isWorldWatched(ServerWorld world) {
        for (PlayerState state : STATES.values()) {
            for (Watch watch : state.watches.values()) {
                if (watch.world == world) {
                    return true;
                }
            }
        }
        return false;
    }

    public static int sendToShadowPlayers(
            ServerWorld sourceWorld,
            Entity excluded,
            double x,
            double y,
            double z,
            double distance,
            Packet<?> packet) {
        CellPos sourceCell = CellWorldKey.cell(sourceWorld.getRegistryKey());
        int localChunkX = MathHelper.floor(x) >> 4;
        int localChunkZ = MathHelper.floor(z) >> 4;
        if (!VirtualChunkPos.isCanonical(localChunkX, localChunkZ)) {
            return 0;
        }
        VirtualChunkPos sourceChunk = new VirtualChunkPos(sourceCell, localChunkX, localChunkZ);
        double squaredRange = distance * distance;
        int sent = 0;
        for (PlayerState state : STATES.values()) {
            ServerPlayerEntity player = state.player;
            Watch watch = state.watches.get(sourceChunk);
            if (watch == null || !watch.sent || player == excluded
                    || player.getEntityWorld() == sourceWorld
                    || !CellWorldKey.baseWorld(player.getEntityWorld().getRegistryKey())
                    .equals(CellWorldKey.baseWorld(sourceWorld.getRegistryKey()))) {
                continue;
            }
            CellPos playerCell = CellWorldKey.cell(player.getEntityWorld().getRegistryKey());
            double dx = x + (sourceCell.x() - playerCell.x()) * (double) org.devt.largerworld.coordinate.VirtualPosition.CELL_SIZE
                    - player.getX();
            double dy = y - player.getY();
            double dz = z + (sourceCell.z() - playerCell.z()) * (double) org.devt.largerworld.coordinate.VirtualPosition.CELL_SIZE
                    - player.getZ();
            if (dx * dx + dy * dy + dz * dz < squaredRange) {
                CellPacketRouting.sendFrom(player, sourceWorld, packet);
                sent++;
            }
        }
        return sent;
    }

    public static void broadcastChunkRefresh(ServerWorld world, WorldChunk chunk) {
        List<ServerPlayerEntity> watchers = watchers(world, chunk.getPos());
        if (watchers.isEmpty()) {
            return;
        }
        ChunkDataS2CPacket packet = new ChunkDataS2CPacket(chunk, world.getLightingProvider(), null, null);
        for (ServerPlayerEntity player : watchers) {
            CellPacketRouting.sendFrom(player, world, packet);
        }
    }

    private static final class PlayerState {
        private ServerPlayerEntity player;
        private final Map<VirtualChunkPos, Watch> watches = new HashMap<>();
        private final Set<CellEntityTracker> trackedEntities = new HashSet<>();
        private final Set<VirtualChunkPos> pendingHandoffChunks = new HashSet<>();
        private int handoffTicks;

        private PlayerState(ServerPlayerEntity player) {
            this.player = player;
        }

        private void addOrSend(MinecraftServer server, VirtualChunkPos pos) {
            Watch watch = watches.get(pos);
            if (watch == null) {
                ServerWorld world = CellWorldManager.getOrCreate(
                        server,
                        CellWorldKey.baseWorld(player.getEntityWorld().getRegistryKey()),
                        pos.cell());
                ChunkPos local = new ChunkPos(pos.localX(), pos.localZ());
                addTickets(world, local);
                watch = new Watch(world, local);
                watches.put(pos, watch);
            }
            addTickets(watch.world, watch.localPos);
            if (!watch.sent) {
                WorldChunk chunk = watch.world.getChunkManager().chunkLoadingManager
                        .getPostProcessedChunk(watch.localPos.toLong());
                if (chunk != null) {
                    CellPacketRouting.sendFrom(player, watch.world,
                            new ChunkDataS2CPacket(chunk, watch.world.getLightingProvider(), null, null));
                    watch.sent = true;
                }
            }
        }

        private void updateWatches(MinecraftServer server, Set<VirtualChunkPos> desired) {
            for (VirtualChunkPos old : new ArrayList<>(watches.keySet())) {
                if (!desired.contains(old)) {
                    remove(old);
                }
            }
            for (VirtualChunkPos wanted : desired) {
                addOrSend(server, wanted);
            }
        }

        private void claimVanillaChunk(MinecraftServer server, VirtualChunkPos pos) {
            Watch watch = watches.get(pos);
            if (watch == null) {
                ServerWorld world = CellWorldManager.getOrCreate(
                        server,
                        CellWorldKey.baseWorld(player.getEntityWorld().getRegistryKey()),
                        pos.cell());
                watch = new Watch(world, new ChunkPos(pos.localX(), pos.localZ()));
                watches.put(pos, watch);
            }
            addTickets(watch.world, watch.localPos);
            watch.sent = true;
        }

        private void beginHandoff(CellPos targetCell) {
            pendingHandoffChunks.clear();
            for (Map.Entry<VirtualChunkPos, Watch> entry : watches.entrySet()) {
                if (entry.getKey().cell().equals(targetCell) && entry.getValue().sent) {
                    pendingHandoffChunks.add(entry.getKey());
                }
            }
            // Chunk ownership updates can be flushed on the following server
            // ticks. Keep a bounded grace period, and consume each duplicate
            // at most once, so later real full-chunk refreshes still pass.
            handoffTicks = 40;
        }

        private boolean consumeHandoffChunk(VirtualChunkPos pos) {
            return handoffTicks > 0 && pendingHandoffChunks.remove(pos);
        }

        private void tickHandoff() {
            if (handoffTicks > 0 && --handoffTicks == 0) {
                pendingHandoffChunks.clear();
                Int2ObjectMap<?> trackers = ((ServerChunkLoadingManagerAccessor)
                        player.getEntityWorld().getChunkManager().chunkLoadingManager)
                        .largerworld$getEntityTrackers();
                for (Object value : trackers.values()) {
                    ((CellEntityTracker) value).largerworld$refreshTracking(player);
                }
            }
        }

        private void remove(VirtualChunkPos pos) {
            Watch watch = watches.remove(pos);
            if (watch == null) {
                return;
            }
            CellPos current = CellWorldKey.cell(player.getEntityWorld().getRegistryKey());
            boolean handedToVanilla = pos.cell().equals(current)
                    && shouldRetain(player, pos.cell(), watch.localPos);
            if (watch.sent && !handedToVanilla) {
                CellPacketRouting.sendUnload(player, watch.world, new UnloadChunkS2CPacket(watch.localPos));
            }
        }

        private void updateEntities() {
            Set<CellEntityTracker> desired = new HashSet<>();
            Map<ServerWorld, Set<Long>> byWorld = new HashMap<>();
            for (Watch watch : watches.values()) {
                if (watch.sent) {
                    byWorld.computeIfAbsent(watch.world, ignored -> new HashSet<>()).add(watch.localPos.toLong());
                }
            }

            for (Map.Entry<ServerWorld, Set<Long>> entry : byWorld.entrySet()) {
                ServerWorld world = entry.getKey();
                Int2ObjectMap<?> trackers = ((ServerChunkLoadingManagerAccessor)
                        world.getChunkManager().chunkLoadingManager).largerworld$getEntityTrackers();
                for (Object value : trackers.values()) {
                    CellEntityTracker tracker = (CellEntityTracker) value;
                    Entity entity = tracker.largerworld$getEntity();
                    if (entity != player && entry.getValue().contains(entity.getChunkPos().toLong())) {
                        desired.add(tracker);
                        if (!trackedEntities.contains(tracker)) {
                            CellPacketRouting.withSource(world, () -> tracker.largerworld$startShadowTracking(player));
                        }
                    }
                }
            }

            for (CellEntityTracker tracker : new ArrayList<>(trackedEntities)) {
                if (!desired.contains(tracker)) {
                    Entity entity = tracker.largerworld$getEntity();
                    boolean handedToVanilla = entity.getEntityWorld() == player.getEntityWorld()
                            && shouldRetain(player,
                            CellWorldKey.cell(player.getEntityWorld().getRegistryKey()),
                            entity.getChunkPos());
                    tracker.largerworld$stopShadowTracking(player, handedToVanilla);
                }
            }
            trackedEntities.clear();
            trackedEntities.addAll(desired);
        }

        private void releaseAll() {
            for (CellEntityTracker tracker : trackedEntities) {
                tracker.largerworld$stopShadowTracking(player, false);
            }
            trackedEntities.clear();
            watches.clear();
            pendingHandoffChunks.clear();
            handoffTicks = 0;
            CellInteractionRouting.forget(player);
            CellPacketRouting.forget(player);
        }
    }

    private static void addTickets(ServerWorld world, ChunkPos pos) {
        world.getChunkManager().addTicket(CellChunkTickets.SHADOW, pos, 0);
    }

    private static final class Watch {
        private final ServerWorld world;
        private final ChunkPos localPos;
        private boolean sent;

        private Watch(ServerWorld world, ChunkPos localPos) {
            this.world = world;
            this.localPos = localPos;
        }
    }

}
