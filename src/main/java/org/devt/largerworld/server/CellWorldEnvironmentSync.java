package org.devt.largerworld.server;

import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.GameStateChangeS2CPacket;
import net.minecraft.network.packet.s2c.play.WorldBorderCenterChangedS2CPacket;
import net.minecraft.network.packet.s2c.play.WorldBorderInitializeS2CPacket;
import net.minecraft.network.packet.s2c.play.WorldBorderInterpolateSizeS2CPacket;
import net.minecraft.network.packet.s2c.play.WorldBorderSizeChangedS2CPacket;
import net.minecraft.network.packet.s2c.play.WorldBorderWarningBlocksChangedS2CPacket;
import net.minecraft.network.packet.s2c.play.WorldBorderWarningTimeChangedS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.border.WorldBorder;
import net.minecraft.world.border.WorldBorderListener;

import java.util.ArrayList;

/** Keeps the active cell's independently persisted weather and border visible to its players. */
public final class CellWorldEnvironmentSync {
    private CellWorldEnvironmentSync() {
    }

    public static void registerBorderListener(ServerWorld world) {
        world.getWorldBorder().addListener(new Listener(world));
    }

    /** Required after a seamless cross-world handoff because vanilla sends this only on join/respawn. */
    public static void sendCurrent(ServerPlayerEntity player, ServerWorld world) {
        CellPacketRouting.sendFrom(
                player, world, new WorldBorderInitializeS2CPacket(world.getWorldBorder()));
        CellPacketRouting.sendFrom(player, world, new GameStateChangeS2CPacket(
                world.isRaining()
                        ? GameStateChangeS2CPacket.RAIN_STARTED
                        : GameStateChangeS2CPacket.RAIN_STOPPED,
                0.0F));
        CellPacketRouting.sendFrom(player, world, new GameStateChangeS2CPacket(
                GameStateChangeS2CPacket.RAIN_GRADIENT_CHANGED,
                world.getRainGradient(1.0F)));
        CellPacketRouting.sendFrom(player, world, new GameStateChangeS2CPacket(
                GameStateChangeS2CPacket.THUNDER_GRADIENT_CHANGED,
                world.getThunderGradient(1.0F)));
    }

    private static final class Listener implements WorldBorderListener {
        private final ServerWorld world;

        private Listener(ServerWorld world) {
            this.world = world;
        }

        @Override
        public void onSizeChange(WorldBorder border, double size) {
            send(new WorldBorderSizeChangedS2CPacket(border));
        }

        @Override
        public void onInterpolateSize(
                WorldBorder border, double fromSize, double toSize, long time, long startTime) {
            send(new WorldBorderInterpolateSizeS2CPacket(border));
        }

        @Override
        public void onCenterChanged(WorldBorder border, double centerX, double centerZ) {
            send(new WorldBorderCenterChangedS2CPacket(border));
        }

        @Override
        public void onWarningTimeChanged(WorldBorder border, int warningTime) {
            send(new WorldBorderWarningTimeChangedS2CPacket(border));
        }

        @Override
        public void onWarningBlocksChanged(WorldBorder border, int warningBlocks) {
            send(new WorldBorderWarningBlocksChangedS2CPacket(border));
        }

        @Override
        public void onDamagePerBlockChanged(WorldBorder border, double damagePerBlock) {
        }

        @Override
        public void onSafeZoneChanged(WorldBorder border, double safeZone) {
        }

        private void send(Packet<?> packet) {
            // Snapshot prevents a dimension change during packet delivery from
            // mutating the list being iterated by the border command.
            for (ServerPlayerEntity player : new ArrayList<>(world.getPlayers())) {
                CellPacketRouting.sendFrom(player, world, packet);
            }
        }
    }
}
