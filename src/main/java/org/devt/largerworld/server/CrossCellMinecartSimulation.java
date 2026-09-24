package org.devt.largerworld.server;

import net.minecraft.entity.Entity;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.devt.largerworld.world.CellBoundaryAccess;

/** Keeps a visible minecart ticking while its observer is in an adjacent cell. */
public final class CrossCellMinecartSimulation {
    // FULL (33) - 3 = 30, enough for this chunk and its neighbors to tick.
    private static final int TICKET_RADIUS = 3;

    private CrossCellMinecartSimulation() {
    }

    public static void refresh(Entity entity) {
        if (!(entity instanceof AbstractMinecartEntity)
                || entity.isRemoved()
                || !(entity.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }
        int simulationDistance = world.getServer().getPlayerManager().getSimulationDistance();
        ChunkPos cartChunk = entity.getChunkPos();
        for (ServerPlayerEntity player : world.getServer().getPlayerManager().getPlayerList()) {
            if (player.isSpectator() || player.isRemoved() || player.getEntityWorld() == world) {
                continue;
            }
            var projected = CellBoundaryAccess.project(player, world);
            if (projected.isEmpty()) {
                continue;
            }
            Vec3d playerPosition = projected.get();
            int playerChunkX = MathHelper.floor(playerPosition.x) >> 4;
            int playerChunkZ = MathHelper.floor(playerPosition.z) >> 4;
            if (Math.abs((long) cartChunk.x - playerChunkX) <= simulationDistance
                    && Math.abs((long) cartChunk.z - playerChunkZ) <= simulationDistance) {
                world.getChunkManager().addTicket(
                        CellChunkTickets.MINECART, cartChunk, TICKET_RADIUS);
                return;
            }
        }
    }
}
