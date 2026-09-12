package org.devt.largerworld.server;

import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.devt.largerworld.world.CellBoundaryAccess;

/** Extends nearby players' simulation to projectiles in an adjacent cell. */
public final class CrossCellProjectileSimulation {
    // FULL (33) - 3 = 30: the current chunk AND its eight neighbors reach
    // entity-ticking level (<= 31). A flying projectile must not stop at the
    // next chunk edge while the client continues its visual trajectory.
    private static final int TICKET_RADIUS = 3;

    private CrossCellProjectileSimulation() {
    }

    public static void refresh(Entity entity) {
        if (!(entity instanceof ProjectileEntity)
                || entity.isRemoved()
                || !(entity.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }
        int simulationDistance = world.getServer().getPlayerManager().getSimulationDistance();
        ChunkPos projectileChunk = entity.getChunkPos();
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
            if (Math.abs((long) projectileChunk.x - playerChunkX) <= simulationDistance
                    && Math.abs((long) projectileChunk.z - playerChunkZ) <= simulationDistance) {
                // No future or synchronous chunk wait per projectile tick.
                // Vanilla propagates this short-lived ticket on its next pass.
                world.getChunkManager().addTicket(
                        CellChunkTickets.PROJECTILE, projectileChunk, TICKET_RADIUS);
                return;
            }
        }
    }
}
