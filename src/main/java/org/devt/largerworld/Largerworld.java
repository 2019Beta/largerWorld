package org.devt.largerworld;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.util.Identifier;
import org.devt.largerworld.command.LargerWorldCommands;
import org.devt.largerworld.coordinate.CellPos;
import org.devt.largerworld.network.CellPacketPayload;
import org.devt.largerworld.network.ContinuousEntityHandoffPayload;
import org.devt.largerworld.network.EntityHandoffPayload;
import org.devt.largerworld.server.OriginShiftService;
import org.devt.largerworld.server.CellViewTracker;
import org.devt.largerworld.server.CellChunkTickets;
import org.devt.largerworld.server.CellChunkTaskEngine;
import org.devt.largerworld.server.CellChunkIoQueue;
import org.devt.largerworld.server.CellRegionIoPrefetch;
import org.devt.largerworld.server.CellInteractionRouting;
import org.devt.largerworld.server.CellPacketRouting;
import org.devt.largerworld.server.CellTickSchedulerRouting;
import org.devt.largerworld.server.CamelHandoffGrace;
import org.devt.largerworld.world.CellWorldManager;
import org.devt.largerworld.world.WorldgenCoordinates;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Largerworld implements ModInitializer {
    public static final String MOD_ID = "largerworld";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    /**
     * Enables verbose logs emitted once per entity handoff/packet. Disabled by
     * default because these diagnostics can generate a large amount of log I/O.
     * Enable with {@code -Dlargerworld.entityInfoLogging=true} when debugging.
     */
    private static final boolean ENTITY_INFO_LOGGING =
            Boolean.getBoolean("largerworld.entityInfoLogging");

    public static void logEntityInfo(String message, Object... arguments) {
        if (ENTITY_INFO_LOGGING) {
            LOGGER.info(message, arguments);
        }
    }

    /**
     * Persistent per-player origin. Fabric synchronizes it to the owning client,
     * so the HUD never needs to receive the very large global coordinate itself.
     */
    public static final AttachmentType<CellPos> CELL_POS = AttachmentRegistry
            .<CellPos>builder()
            .persistent(CellPos.CODEC)
            .copyOnDeath()
            .initializer(() -> CellPos.ZERO)
            .syncWith(CellPos.PACKET_CODEC, AttachmentSyncPredicate.targetOnly())
            .buildAndRegister(Identifier.of(MOD_ID, "cell_pos"));

    @Override
    public void onInitialize() {
        CellPacketPayload.register();
        EntityHandoffPayload.register();
        ContinuousEntityHandoffPayload.register();
        CellChunkTickets.register();
        LargerWorldCommands.register();
        ServerTickEvents.START_SERVER_TICK.register(OriginShiftService::reconcilePlayerWorlds);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            OriginShiftService.tick(server);
            CellViewTracker.tick(server);
            CellWorldManager.tickEviction(server);
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            CellWorldManager.clearServerState(server);
            CellChunkTaskEngine.clearServerState(server);
            CellChunkIoQueue.clearServerState();
            CellRegionIoPrefetch.clearServerState();
            CellViewTracker.clearServerState();
            OriginShiftService.clearServerState();
            CellInteractionRouting.clearServerState();
            CellPacketRouting.clearServerState();
            CellTickSchedulerRouting.clearServerState();
            CamelHandoffGrace.clearServerState();
            WorldgenCoordinates.clearServerState();
        });
    }
}
