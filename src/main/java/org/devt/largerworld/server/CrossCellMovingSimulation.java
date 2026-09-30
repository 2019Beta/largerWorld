package org.devt.largerworld.server;

import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.devt.largerworld.world.CellBoundaryAccess;

/** Keeps visible projectiles and minecarts ticking across adjacent cells. */
public final class CrossCellMovingSimulation {
    // FULL (33) - 3 = 30, so the current chunk and its neighbors can tick.
    private static final int TICKET_RADIUS = 3;

    private CrossCellMovingSimulation() {
    }

    public static void refresh(Entity entity) {
        ChunkTicketType ticket;
        if (entity instanceof ProjectileEntity) {
            ticket = CellChunkTickets.PROJECTILE;
        } else if (entity instanceof AbstractMinecartEntity) {
            ticket = CellChunkTickets.MINECART;
        } else {
            return;
        }
        if (entity.isRemoved() || !(entity.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }
        int simulationDistance = world.getServer().getPlayerManager().getSimulationDistance();
        ChunkPos entityChunk = entity.getChunkPos();
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
            if (Math.abs((long) entityChunk.x - playerChunkX) <= simulationDistance
                    && Math.abs((long) entityChunk.z - playerChunkZ) <= simulationDistance) {
                // Let vanilla propagate the short-lived ticket on its next pass.
                world.getChunkManager().addTicket(ticket, entityChunk, TICKET_RADIUS);
                return;
            }
        }
    }
}
