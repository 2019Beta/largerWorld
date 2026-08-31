package org.devt.largerworld.server;

import net.minecraft.entity.Entity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.devt.largerworld.coordinate.CellPos;
import org.devt.largerworld.coordinate.VirtualChunkPos;
import org.devt.largerworld.coordinate.VirtualPosition;
import org.devt.largerworld.world.CellWorldKey;
import org.devt.largerworld.world.CellWorldManager;

import java.util.HashSet;
import java.util.Set;

/** Predicts seam crossings and prepares a small target-cell entry region. */
public final class CellPrefetchPlanner {
    private static final int INTERVAL_TICKS = Math.max(1,
            Integer.getInteger("largerworld.prefetchIntervalTicks", 5));
    private static final int HORIZON_TICKS = Math.max(1,
            Integer.getInteger("largerworld.prefetchHorizonTicks", 60));
    private static final int ENTRY_RADIUS_CHUNKS = Math.max(0,
            Integer.getInteger("largerworld.prefetchRadiusChunks", 2));

    private CellPrefetchPlanner() {
    }

    public static void tick(MinecraftServer server) {
        long ticks = server.getTicks();
        if (ticks % INTERVAL_TICKS == 0) {
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                prefetchPlayer(server, player);
            }
        }
        if (ticks % 20 == 0) {
            CellRegionIoPrefetch.evictExpired();
        }
    }

    private static void prefetchPlayer(MinecraftServer server, ServerPlayerEntity player) {
        ServerWorld sourceWorld = player.getEntityWorld();
        Entity motionSource = player.getRootVehicle();
        Vec3d velocity = motionSource.getVelocity();
        Prediction prediction = predict(
                motionSource.getX(), motionSource.getZ(),
                velocity.x, velocity.z,
                player.getViewDistance(), HORIZON_TICKS);
        if (!prediction.crossesCell()) {
            return;
        }

        CellPos sourceCell = CellWorldKey.cell(sourceWorld.getRegistryKey());
        CellPos predictedCell = sourceCell.add(prediction.deltaX(), prediction.deltaZ());
        RegistryKey<World> baseWorld = CellWorldKey.baseWorld(sourceWorld.getRegistryKey());
        int entryChunkX = MathHelper.floor(prediction.entryLocalX()) >> 4;
        int entryChunkZ = MathHelper.floor(prediction.entryLocalZ()) >> 4;
        Set<VirtualChunkPos> requested = new HashSet<>();

        for (int dz = -ENTRY_RADIUS_CHUNKS; dz <= ENTRY_RADIUS_CHUNKS; dz++) {
            for (int dx = -ENTRY_RADIUS_CHUNKS; dx <= ENTRY_RADIUS_CHUNKS; dx++) {
                requested.add(VirtualChunkPos.fromClient(
                        predictedCell, entryChunkX + dx, entryChunkZ + dz));
            }
        }

        for (VirtualChunkPos virtual : requested) {
            ServerWorld target;
            try {
                target = CellWorldManager.getOrCreate(server, baseWorld, virtual.cell());
            } catch (CellWorldManager.CellCapacityException exception) {
                continue;
            }
            CellChunkTaskEngine.prefetchAccessible(
                    target, new ChunkPos(virtual.localX(), virtual.localZ()));
        }
    }

    static Prediction predict(
            double localX,
            double localZ,
            double velocityX,
            double velocityZ,
            int viewDistanceChunks,
            int horizonTicks) {
        double margin = (Math.max(2, viewDistanceChunks) + 2) * 16.0;
        int deltaX = crossingDirection(
                localX, velocityX, margin, horizonTicks);
        int deltaZ = crossingDirection(
                localZ, velocityZ, margin, horizonTicks);
        if (deltaX == 0 && deltaZ == 0) {
            return Prediction.NONE;
        }

        double projectedX = clampToCell(localX + velocityX * horizonTicks);
        double projectedZ = clampToCell(localZ + velocityZ * horizonTicks);
        double entryX = deltaX > 0 ? -VirtualPosition.HALF_CELL + 1.0
                : deltaX < 0 ? VirtualPosition.HALF_CELL - 1.0 : projectedX;
        double entryZ = deltaZ > 0 ? -VirtualPosition.HALF_CELL + 1.0
                : deltaZ < 0 ? VirtualPosition.HALF_CELL - 1.0 : projectedZ;
        return new Prediction(deltaX, deltaZ, entryX, entryZ);
    }

    private static int crossingDirection(
            double position,
            double velocity,
            double margin,
            int horizonTicks) {
        double positiveTrigger = VirtualPosition.HALF_CELL - margin;
        double negativeTrigger = -VirtualPosition.HALF_CELL + margin;
        if (position >= positiveTrigger
                || velocity > 0.0 && position + velocity * horizonTicks >= positiveTrigger) {
            return 1;
        }
        if (position <= negativeTrigger
                || velocity < 0.0 && position + velocity * horizonTicks <= negativeTrigger) {
            return -1;
        }
        return 0;
    }

    private static double clampToCell(double coordinate) {
        return Math.max(-VirtualPosition.HALF_CELL + 1.0,
                Math.min(VirtualPosition.HALF_CELL - 1.0, coordinate));
    }

    record Prediction(int deltaX, int deltaZ, double entryLocalX, double entryLocalZ) {
        private static final Prediction NONE = new Prediction(0, 0, 0.0, 0.0);

        boolean crossesCell() {
            return deltaX != 0 || deltaZ != 0;
        }
    }
}
