package org.devt.largerworld;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.util.Identifier;
import org.devt.largerworld.command.LargerWorldCommands;
import org.devt.largerworld.coordinate.CellPos;
import org.devt.largerworld.server.OriginShiftService;
import org.devt.largerworld.world.CellWorldManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Largerworld implements ModInitializer {
    public static final String MOD_ID = "largerworld";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

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
        LargerWorldCommands.register();
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            OriginShiftService.tick(server);
            CellWorldManager.tickEviction(server);
        });
    }
}
