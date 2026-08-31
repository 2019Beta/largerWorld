package org.devt.largerworld.server;

import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.util.Identifier;
import org.devt.largerworld.Largerworld;

/** Explicitly managed, non-persistent tickets used by neighboring-cell shadow views. */
public final class CellChunkTickets {
    private static final long PREFETCH_EXPIRY_TICKS = 20L * 15L;

    public static final ChunkTicketType SHADOW = Registry.register(
            Registries.TICKET_TYPE,
            Identifier.of(Largerworld.MOD_ID, "shadow_view"),
            new ChunkTicketType(
                    ChunkTicketType.NO_EXPIRATION,
                    ChunkTicketType.FOR_LOADING
                            | ChunkTicketType.RESETS_IDLE_TIMEOUT));
    public static final ChunkTicketType PREFETCH = Registry.register(
            Registries.TICKET_TYPE,
            Identifier.of(Largerworld.MOD_ID, "predicted_prefetch"),
            new ChunkTicketType(
                    PREFETCH_EXPIRY_TICKS,
                    ChunkTicketType.FOR_LOADING
                            | ChunkTicketType.RESETS_IDLE_TIMEOUT));

    private CellChunkTickets() {
    }

    public static void register() {
        // Forces static registration during mod initialization.
    }
}
