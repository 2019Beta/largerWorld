package org.devt.largerworld.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;
import org.devt.largerworld.Largerworld;
import org.devt.largerworld.coordinate.CellPos;
import org.devt.largerworld.coordinate.VirtualPosition;
import org.devt.largerworld.client.network.ClientCellPacketContext;
import org.devt.largerworld.client.network.ClientContinuousEntityHandoff;
import org.devt.largerworld.client.network.ClientEntityHandoff;
import org.devt.largerworld.network.CellPacketPayload;
import org.devt.largerworld.network.ContinuousEntityHandoffPayload;
import org.devt.largerworld.network.EntityHandoffPayload;
import org.devt.largerworld.network.OriginRebasePayload;
import org.devt.largerworld.client.network.ClientOriginRebase;

import java.util.Locale;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

public class LargerworldClient implements ClientModInitializer {
    private static final int PRIMARY_TEXT_COLOR = 0xFFFFFFFF;
    private static final int SECONDARY_TEXT_COLOR = 0xFFA0FFA0;

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(CellPacketPayload.ID,
                (payload, context) -> ClientCellPacketContext.apply(payload, context.client().getNetworkHandler()));
        ClientPlayNetworking.registerGlobalReceiver(EntityHandoffPayload.ID,
                (payload, context) -> ClientEntityHandoff.accept(payload));
        ClientPlayNetworking.registerGlobalReceiver(ContinuousEntityHandoffPayload.ID,
                (payload, context) -> ClientContinuousEntityHandoff.accept(payload));
        ClientPlayNetworking.registerGlobalReceiver(OriginRebasePayload.ID,
                (payload, context) -> ClientOriginRebase.apply(context.client(), payload));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ClientCellPacketContext.reset();
            ClientEntityHandoff.clear();
            ClientContinuousEntityHandoff.tick(null);
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ClientContinuousEntityHandoff.tick(client.world);
            ClientEntityHandoff.tick(client.getNetworkHandler());
        });
        HudElementRegistry.addLast(
                Identifier.of(Largerworld.MOD_ID, "global_coordinates"),
                (context, tickCounter) -> {
                    MinecraftClient client = MinecraftClient.getInstance();
                    if (client.player == null) {
                        return;
                    }

                    CellPos cell = client.player.getAttachedOrCreate(Largerworld.CELL_POS);
                    VirtualPosition position = VirtualPosition.normalize(
                            ClientCellPacketContext.connectionOrigin(cell),
                            client.player.getX(), client.player.getY(), client.player.getZ());
                    String global = "实际 XYZ: " + compactCoordinate(position.globalX()) + " / "
                            + String.format(Locale.ROOT, "%.3f", position.y()) + " / "
                            + compactCoordinate(position.globalZ());
                    String local = "Cell: [" + compactCoordinate(new BigDecimal(cell.x())) + ", "
                            + compactCoordinate(new BigDecimal(cell.z())) + "]  Local XZ: "
                            + String.format(Locale.ROOT, "%.3f / %.3f", position.localX(), position.localZ());

                    if (client.getDebugHud().shouldShowDebugHud()) {
                        int y = context.getScaledWindowHeight() - 22;
                        context.drawTextWithShadow(client.textRenderer, global, 2, y, PRIMARY_TEXT_COLOR);
                        context.drawTextWithShadow(client.textRenderer, local, 2, y + 10, SECONDARY_TEXT_COLOR);
                        return;
                    }

                    int panelX = 4;
                    int panelY = 4;
                    int panelWidth = Math.max(
                            client.textRenderer.getWidth(global),
                            client.textRenderer.getWidth(local)) + 8;
                    context.fill(panelX, panelY, panelX + panelWidth, panelY + 25, 0x90000000);
                    context.drawTextWithShadow(
                            client.textRenderer, global, panelX + 4, panelY + 3, PRIMARY_TEXT_COLOR);
                    context.drawTextWithShadow(
                            client.textRenderer, local, panelX + 4, panelY + 13, SECONDARY_TEXT_COLOR);
                });
    }

    private static String compactCoordinate(BigDecimal coordinate) {
        if ((long) coordinate.precision() - coordinate.scale() <= 18) {
            return coordinate.setScale(Math.max(0, Math.min(3, coordinate.scale())),
                    RoundingMode.HALF_UP).toPlainString();
        }
        return coordinate.round(new MathContext(12)).toEngineeringString();
    }
}
