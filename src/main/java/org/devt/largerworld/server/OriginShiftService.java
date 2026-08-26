package org.devt.largerworld.server;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.devt.largerworld.Largerworld;
import org.devt.largerworld.coordinate.CellPos;
import org.devt.largerworld.coordinate.VirtualPosition;

/** Keeps every server-side player inside the numerically stable local cell. */
public final class OriginShiftService {
    private OriginShiftService() {
    }

    public static void tick(MinecraftServer server) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            shiftIfNeeded(player);
        }
    }

    public static boolean shiftIfNeeded(ServerPlayerEntity player) {
        CellPos currentCell = player.getAttachedOrCreate(Largerworld.CELL_POS);
        VirtualPosition normalized = VirtualPosition.normalize(
                currentCell, player.getX(), player.getY(), player.getZ());

        if (normalized.isInCell(currentCell)) {
            return false;
        }

        // Moving only the rider leaves the mount in the old local window. The
        // MVP deliberately dismounts; vehicle-aware cell migration is phase 2.
        if (player.hasVehicle()) {
            player.dismountVehicle();
        }

        player.setAttached(Largerworld.CELL_POS, normalized.cell());
        player.requestTeleport(normalized.localX(), normalized.y(), normalized.localZ());
        return true;
    }
}
