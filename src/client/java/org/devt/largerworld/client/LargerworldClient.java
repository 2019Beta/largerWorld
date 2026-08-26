package org.devt.largerworld.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;
import org.devt.largerworld.Largerworld;
import org.devt.largerworld.coordinate.CellPos;
import org.devt.largerworld.coordinate.VirtualPosition;

import java.util.Locale;

public class LargerworldClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        HudElementRegistry.attachElementAfter(
                VanillaHudElements.SUBTITLES,
                Identifier.of(Largerworld.MOD_ID, "global_coordinates"),
                (context, tickCounter) -> {
                    MinecraftClient client = MinecraftClient.getInstance();
                    if (client.player == null || !client.getDebugHud().shouldShowDebugHud()) {
                        return;
                    }

                    CellPos cell = client.player.getAttachedOrCreate(Largerworld.CELL_POS);
                    VirtualPosition position = VirtualPosition.normalize(
                            cell, client.player.getX(), client.player.getY(), client.player.getZ());
                    int y = context.getScaledWindowHeight() - 22;
                    String global = "Global XYZ: " + position.globalX(3) + " / "
                            + String.format(Locale.ROOT, "%.3f", position.y()) + " / " + position.globalZ(3);
                    String local = "Cell: [" + cell.x() + ", " + cell.z() + "]  Local XZ: "
                            + String.format(Locale.ROOT, "%.3f / %.3f", position.localX(), position.localZ());
                    context.drawTextWithShadow(client.textRenderer, global, 2, y, 0xFFFFFF);
                    context.drawTextWithShadow(client.textRenderer, local, 2, y + 10, 0xA0FFA0);
                });
    }
}
