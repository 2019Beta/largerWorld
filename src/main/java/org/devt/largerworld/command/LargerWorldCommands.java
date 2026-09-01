package org.devt.largerworld.command;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.permission.Permission;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import org.devt.largerworld.Largerworld;
import org.devt.largerworld.coordinate.CellPos;
import org.devt.largerworld.coordinate.VirtualPosition;
import org.devt.largerworld.server.OriginShiftService;
import org.devt.largerworld.server.CellChunkTaskEngine;
import org.devt.largerworld.world.CellWorldKey;
import org.devt.largerworld.world.CellWorldManager;

import java.math.BigDecimal;
import java.util.Locale;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public final class LargerWorldCommands {
    private LargerWorldCommands() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                literal("largerworld")
                        .then(literal("coords").executes(LargerWorldCommands::showCoordinates))
                        .then(literal("teleport")
                                .requires(source -> source.getPermissions().hasPermission(
                                        new Permission.Level(PermissionLevel.GAMEMASTERS)))
                                .then(argument("globalX", StringArgumentType.word())
                                        .then(argument("y", DoubleArgumentType.doubleArg())
                                                .then(argument("globalZ", StringArgumentType.word())
                                                        .executes(LargerWorldCommands::teleport)))))));
    }

    private static int showCoordinates(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
        CellPos cell = player.getAttachedOrCreate(Largerworld.CELL_POS);
        VirtualPosition position = VirtualPosition.normalize(cell, player.getX(), player.getY(), player.getZ());
        context.getSource().sendFeedback(() -> Text.literal(
                "Global: " + position.globalX(3) + " / " + String.format(Locale.ROOT, "%.3f", position.y())
                        + " / " + position.globalZ(3)
                        + "  Cell: [" + position.cell().x() + ", " + position.cell().z() + "]"), false);
        return 1;
    }

    private static int teleport(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
        try {
            BigDecimal globalX = new BigDecimal(StringArgumentType.getString(context, "globalX"));
            BigDecimal globalZ = new BigDecimal(StringArgumentType.getString(context, "globalZ"));
            double y = DoubleArgumentType.getDouble(context, "y");
            VirtualPosition target = VirtualPosition.fromGlobal(globalX, y, globalZ);

            ServerWorld currentWorld = player.getEntityWorld();
            ServerWorld targetWorld = CellWorldManager.getOrCreate(
                    context.getSource().getServer(),
                    CellWorldKey.baseWorld(currentWorld.getRegistryKey()),
                    target.cell());
            ChunkPos landingChunk = new ChunkPos(
                    MathHelper.floor(target.localX()) >> 4,
                    MathHelper.floor(target.localZ()) >> 4);
            var server = context.getSource().getServer();
            var source = context.getSource();
            CellChunkTaskEngine.prepareAccessible(targetWorld, landingChunk)
                    .whenComplete((ignored, error) -> server.execute(() -> {
                        if (error != null) {
                            source.sendError(Text.literal(
                                    "The target chunk could not be prepared: "
                                            + rootMessage(error)));
                            return;
                        }
                        if (!player.networkHandler.isConnectionOpen()) {
                            return;
                        }
                        if (!OriginShiftService.teleportGraph(
                                player.getRootVehicle(), targetWorld,
                                target.localX(), target.y(), target.localZ(), target.cell())) {
                            source.sendError(Text.literal("The target cell could not be loaded"));
                            return;
                        }
                        source.sendFeedback(() -> Text.literal(
                                "Teleported to global " + target.globalX(3) + " / "
                                        + String.format(Locale.ROOT, "%.3f", target.y())
                                        + " / " + target.globalZ(3)), true);
                    }));
            context.getSource().sendFeedback(
                    () -> Text.literal("Preparing the target chunk..."), false);
            return 1;
        } catch (ArithmeticException | IllegalArgumentException
                 | CellWorldManager.CellCapacityException exception) {
            context.getSource().sendError(Text.literal("Invalid or unsupported global coordinate: "
                    + exception.getMessage()));
            return 0;
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message == null || message.isBlank()
                ? root.getClass().getSimpleName() : message;
    }
}
