package org.devt.largerworld.server;

import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.TeleportTarget;
import org.devt.largerworld.Largerworld;
import org.devt.largerworld.coordinate.CellPos;
import org.devt.largerworld.coordinate.VirtualPosition;
import org.devt.largerworld.world.CellWorldKey;
import org.devt.largerworld.world.CellWorldManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Migrates complete entity/passenger graphs between independently stored cell worlds. */
public final class OriginShiftService {
    private OriginShiftService() {
    }

    public static void tick(MinecraftServer server) {
        if (server.getTicks() % 20 == 0) {
            preloadApproachingCells(server);
        }

        Set<UUID> handledRoots = new HashSet<>();
        ArrayList<ServerWorld> worldSnapshot = new ArrayList<>();
        server.getWorlds().forEach(worldSnapshot::add);
        for (ServerWorld world : worldSnapshot) {
            ArrayList<Entity> entitySnapshot = new ArrayList<>();
            world.iterateEntities().forEach(entitySnapshot::add);
            for (Entity entity : entitySnapshot) {
                if (entity.isRemoved()) {
                    continue;
                }
                Entity root = entity.getRootVehicle();
                if (!handledRoots.add(root.getUuid())) {
                    continue;
                }
                shiftIfNeeded(root);
            }
        }
    }

    private static void preloadApproachingCells(MinecraftServer server) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            ServerWorld currentWorld = player.getEntityWorld();
            CellPos currentCell = CellWorldKey.cell(currentWorld.getRegistryKey());
            int margin = (player.getViewDistance() + 2) * 16;
            int deltaX = player.getX() >= VirtualPosition.HALF_CELL - margin ? 1
                    : player.getX() < -VirtualPosition.HALF_CELL + margin ? -1 : 0;
            int deltaZ = player.getZ() >= VirtualPosition.HALF_CELL - margin ? 1
                    : player.getZ() < -VirtualPosition.HALF_CELL + margin ? -1 : 0;

            if (deltaX != 0) {
                preload(server, currentWorld, currentCell, deltaX, 0, player.getZ());
            }
            if (deltaZ != 0) {
                preload(server, currentWorld, currentCell, 0, deltaZ, player.getX());
            }
            if (deltaX != 0 && deltaZ != 0) {
                preload(server, currentWorld, currentCell, deltaX, deltaZ, 0);
            }
        }
    }

    private static void preload(
            MinecraftServer server,
            ServerWorld currentWorld,
            CellPos currentCell,
            int deltaX,
            int deltaZ,
            double unchangedAxis) {
        CellPos targetCell = currentCell.add(deltaX, deltaZ);
        ServerWorld target = CellWorldManager.getOrCreate(
                server, CellWorldKey.baseWorld(currentWorld.getRegistryKey()), targetCell);
        double x = deltaX > 0 ? -VirtualPosition.HALF_CELL + 1
                : deltaX < 0 ? VirtualPosition.HALF_CELL - 1 : unchangedAxis;
        double z = deltaZ > 0 ? -VirtualPosition.HALF_CELL + 1
                : deltaZ < 0 ? VirtualPosition.HALF_CELL - 1 : unchangedAxis;
        ChunkPos entryChunk = new ChunkPos(MathHelper.floor(x) >> 4, MathHelper.floor(z) >> 4);
        target.getChunkManager().addTicket(ChunkTicketType.PORTAL, entryChunk, 3);
    }

    public static void reconcilePlayerWorlds(MinecraftServer server) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            ServerWorld currentWorld = player.getEntityWorld();
            CellPos worldCell = CellWorldKey.cell(currentWorld.getRegistryKey());
            CellPos attachedCell = player.getAttachedOrCreate(Largerworld.CELL_POS);

            if (!worldCell.equals(CellPos.ZERO)) {
                if (!worldCell.equals(attachedCell)) {
                    player.setAttached(Largerworld.CELL_POS, worldCell);
                }
            } else if (!attachedCell.equals(CellPos.ZERO)) {
                ServerWorld target = CellWorldManager.getOrCreate(
                        server, CellWorldKey.baseWorld(currentWorld.getRegistryKey()), attachedCell);
                teleportGraph(player.getRootVehicle(), target,
                        player.getRootVehicle().getX(), player.getRootVehicle().getY(), player.getRootVehicle().getZ(),
                        attachedCell);
            }
        }
    }

    public static boolean shiftIfNeeded(Entity root) {
        ServerWorld currentWorld = (ServerWorld) root.getEntityWorld();
        CellPos currentCell = CellWorldKey.cell(currentWorld.getRegistryKey());
        VirtualPosition normalized = VirtualPosition.normalize(
                currentCell, root.getX(), root.getY(), root.getZ());

        if (normalized.isInCell(currentCell)) {
            return false;
        }

        ServerWorld targetWorld = CellWorldManager.getOrCreate(
                currentWorld.getServer(),
                CellWorldKey.baseWorld(currentWorld.getRegistryKey()),
                normalized.cell());
        return teleportGraph(root, targetWorld,
                normalized.localX(), normalized.y(), normalized.localZ(), normalized.cell());
    }

    public static boolean teleportGraph(
            Entity root, ServerWorld targetWorld, double x, double y, double z, CellPos targetCell) {
        for (Entity member : root.streamSelfAndPassengers().toList()) {
            if (member instanceof ServerPlayerEntity player) {
                player.setAttached(Largerworld.CELL_POS, targetCell);
            }
        }

        TeleportTarget target = new TeleportTarget(
                targetWorld,
                new Vec3d(x, y, z),
                root.getVelocity(),
                root.getYaw(),
                root.getPitch(),
                TeleportTarget.NO_OP);
        return root.teleportTo(target) != null;
    }
}
