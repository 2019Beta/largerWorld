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
import net.minecraft.text.Text;
import org.devt.largerworld.Largerworld;
import org.devt.largerworld.coordinate.CellPos;
import org.devt.largerworld.coordinate.VirtualPosition;

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

            if (player.hasVehicle()) {
                player.dismountVehicle();
            }
            player.setAttached(Largerworld.CELL_POS, target.cell());
            player.requestTeleport(target.localX(), target.y(), target.localZ());
            context.getSource().sendFeedback(() -> Text.literal(
                    "Teleported to global " + target.globalX(3) + " / "
                            + String.format(Locale.ROOT, "%.3f", target.y()) + " / " + target.globalZ(3)), true);
            return 1;
        } catch (ArithmeticException | IllegalArgumentException exception) {
            context.getSource().sendError(Text.literal("Invalid or unsupported global coordinate: "
                    + exception.getMessage()));
            return 0;
        }
    }
}
