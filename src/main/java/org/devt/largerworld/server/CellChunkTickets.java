package org.devt.largerworld.server;

import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.util.Identifier;
import org.devt.largerworld.Largerworld;

/** Expiring, non-persistent tickets used by neighboring-cell shadow views. */
public final class CellChunkTickets {
    public static final ChunkTicketType SHADOW = Registry.register(
            Registries.TICKET_TYPE,
            Identifier.of(Largerworld.MOD_ID, "shadow_view"),
            new ChunkTicketType(
                    40L,
                    ChunkTicketType.FOR_LOADING
                            | ChunkTicketType.FOR_SIMULATION
                            | ChunkTicketType.RESETS_IDLE_TIMEOUT));

    private CellChunkTickets() {
    }

    public static void register() {
        // Forces static registration during mod initialization.
    }
}
