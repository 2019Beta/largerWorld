package org.devt.largerworld.server;

import net.minecraft.entity.Entity;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.network.ServerPlayerInteractionManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.devt.largerworld.coordinate.CellPos;
import org.devt.largerworld.coordinate.VirtualPosition;
import org.devt.largerworld.world.CellWorldKey;
import org.devt.largerworld.world.CellWorldManager;
import org.devt.largerworld.world.CellBoundaryAccess;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Routes interactions with visible neighboring-cell content to its backing world. */
public final class CellInteractionRouting {
    private static final ThreadLocal<Boolean> REROUTING = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Boolean> CLOSING_REMOTE_SCREEN =
            ThreadLocal.withInitial(() -> false);
    private static final Map<UUID, RemoteMining> REMOTE_MINING = new HashMap<>();
    private static final Map<UUID, RemoteScreen> REMOTE_SCREEN_WORLDS = new HashMap<>();

    private CellInteractionRouting() {
    }

    public static boolean reroutePlayerAction(
            MinecraftServer server, ServerPlayNetworkHandler handler, PlayerActionC2SPacket packet) {
        if (REROUTING.get() || !isBlockAction(packet.getAction())) {
            return false;
        }

        BlockTarget target = blockTarget(server, handler.player, packet.getPos());
        if (target == null) {
            return false;
        }
        ServerWorld current = handler.player.getEntityWorld();
        if (target.world == current) {
            if (packet.getAction() == PlayerActionC2SPacket.Action.START_DESTROY_BLOCK) {
                REMOTE_MINING.remove(handler.player.getUuid());
            }
            return false;
        }

        PlayerActionC2SPacket translated = new PlayerActionC2SPacket(
                packet.getAction(), target.pos, packet.getDirection(), packet.getSequence());
        boolean keepMiningWorld = packet.getAction() == PlayerActionC2SPacket.Action.START_DESTROY_BLOCK;
        runInWorld(handler.player, target.world, () -> handler.onPlayerAction(translated));
        sendAuthoritativeBlockNeighborhood(handler.player, target.world, target.pos);
        if (keepMiningWorld && !target.world.getBlockState(target.pos).isAir()) {
            REMOTE_MINING.put(
                    handler.player.getUuid(), new RemoteMining(target.world, target.pos));
        } else {
            REMOTE_MINING.remove(handler.player.getUuid());
        }
        return true;
    }

    public static boolean rerouteBlockInteraction(
            MinecraftServer server, ServerPlayNetworkHandler handler, PlayerInteractBlockC2SPacket packet) {
        if (REROUTING.get()) {
            return false;
        }

        BlockHitResult hit = packet.getBlockHitResult();
        BlockTarget target = blockTarget(server, handler.player, hit.getBlockPos());
        if (target == null) {
            return false;
        }
        if (target.world == handler.player.getEntityWorld()) {
            REMOTE_SCREEN_WORLDS.remove(handler.player.getUuid());
            return false;
        }

        Vec3d translatedHitPos = translateToCell(handler.player, target.cell, hit.getPos());
        BlockHitResult translatedHit = new BlockHitResult(
                translatedHitPos,
                hit.getSide(),
                target.pos,
                hit.isInsideBlock(),
                hit.isAgainstWorldBorder());
        PlayerInteractBlockC2SPacket translated = new PlayerInteractBlockC2SPacket(
                packet.getHand(), translatedHit, packet.getSequence());
        ScreenHandler previousScreen = handler.player.currentScreenHandler;
        runInWorld(handler.player, target.world, () -> handler.onPlayerInteractBlock(translated));
        ScreenHandler openedScreen = handler.player.currentScreenHandler;
        if (openedScreen != previousScreen) {
            REMOTE_SCREEN_WORLDS.put(
                    handler.player.getUuid(), new RemoteScreen(target.world, openedScreen));
        } else {
            REMOTE_SCREEN_WORLDS.remove(handler.player.getUuid());
        }
        return true;
    }

    public static boolean rerouteEntityInteraction(
            ServerPlayNetworkHandler handler, PlayerInteractEntityC2SPacket packet) {
        if (REROUTING.get() || packet.getEntity(handler.player.getEntityWorld()) != null) {
            return false;
        }

        Entity entity = CellViewTracker.findVisibleEntity(handler.player, packet);
        if (entity == null || !(entity.getEntityWorld() instanceof ServerWorld targetWorld)) {
            return false;
        }
        ScreenHandler previousScreen = handler.player.currentScreenHandler;
        runInWorld(handler.player, targetWorld, () -> handler.onPlayerInteractEntity(packet));
        ScreenHandler openedScreen = handler.player.currentScreenHandler;
        if (openedScreen != previousScreen) {
            REMOTE_SCREEN_WORLDS.put(
                    handler.player.getUuid(), new RemoteScreen(targetWorld, openedScreen));
        }
        return true;
    }

    public static boolean isRerouting() {
        return REROUTING.get();
    }

    public static void forget(ServerPlayerEntity player) {
        REMOTE_MINING.remove(player.getUuid());
        REMOTE_SCREEN_WORLDS.remove(player.getUuid());
    }

    /** Prevents eviction while a remote mining operation or screen still owns a world reference. */
    public static boolean isWorldInUse(ServerWorld world) {
        return REMOTE_MINING.values().stream().anyMatch(remote -> remote.world() == world)
                || REMOTE_SCREEN_WORLDS.values().stream()
                .anyMatch(remote -> remote.world() == world);
    }

    public static boolean canUseScreen(ServerPlayerEntity player, ScreenHandler handler) {
        RemoteScreen remote = REMOTE_SCREEN_WORLDS.get(player.getUuid());
        if (remote == null || remote.handler() != handler) {
            if (remote != null) {
                REMOTE_SCREEN_WORLDS.remove(player.getUuid());
            }
            return handler.canUse(player);
        }
        ServerWorld target = remote.world();
        if (target == player.getEntityWorld()) {
            REMOTE_SCREEN_WORLDS.remove(player.getUuid());
            return handler.canUse(player);
        }
        boolean[] result = new boolean[1];
        runInWorld(player, target, () -> result[0] = handler.canUse(player));
        return result[0];
    }

    /** Closes a remote menu in the world in which it was opened. */
    public static boolean closeRemoteScreen(ServerPlayerEntity player) {
        if (CLOSING_REMOTE_SCREEN.get()) {
            return false;
        }
        RemoteScreen remote = REMOTE_SCREEN_WORLDS.get(player.getUuid());
        if (remote == null || remote.handler() != player.currentScreenHandler) {
            if (remote != null) {
                REMOTE_SCREEN_WORLDS.remove(player.getUuid());
            }
            return false;
        }

        CLOSING_REMOTE_SCREEN.set(true);
        try {
            runInWorld(player, remote.world(), player::onHandledScreenClosed);
        } finally {
            REMOTE_SCREEN_WORLDS.remove(player.getUuid());
            CLOSING_REMOTE_SCREEN.set(false);
        }
        return true;
    }

    public static void updateInteractionManager(
            ServerPlayerEntity player, ServerPlayerInteractionManager interactionManager) {
        RemoteMining remote = REMOTE_MINING.get(player.getUuid());
        if (remote == null || remote.world() == player.getEntityWorld()) {
            if (remote != null) {
                REMOTE_MINING.remove(player.getUuid());
            }
            interactionManager.update();
            return;
        }
        var before = remote.world().getBlockState(remote.pos());
        runInWorld(player, remote.world(), interactionManager::update);
        var after = remote.world().getBlockState(remote.pos());
        if (!before.equals(after)) {
            sendAuthoritativeBlockNeighborhood(
                    player, remote.world(), remote.pos());
        }
        if (after.isAir()) {
            REMOTE_MINING.remove(player.getUuid());
        }
    }

    private static @Nullable BlockTarget blockTarget(
            MinecraftServer server, ServerPlayerEntity player, BlockPos clientPos) {
        CellPos origin = CellPacketRouting.origin(player);
        CellPos current = CellWorldKey.cell(player.getEntityWorld().getRegistryKey());
        double playerClientX = player.getX()
                + ((double) current.x() - (double) origin.x())
                * (double) VirtualPosition.CELL_SIZE;
        double playerClientZ = player.getZ()
                + ((double) current.z() - (double) origin.z())
                * (double) VirtualPosition.CELL_SIZE;
        double dx = clientPos.getX() - playerClientX;
        double dy = clientPos.getY() - player.getY();
        double dz = clientPos.getZ() - playerClientZ;
        if (dx * dx + dy * dy + dz * dz > 64.0 * 64.0) {
            return null;
        }
        VirtualPosition virtual = VirtualPosition.normalize(
                origin, clientPos.getX(), clientPos.getY(), clientPos.getZ());
        ServerWorld world = CellWorldManager.getOrCreate(
                server,
                CellWorldKey.baseWorld(player.getEntityWorld().getRegistryKey()),
                virtual.cell());
        return new BlockTarget(
                virtual.cell(),
                world,
                new BlockPos(MathHelper.floor(virtual.localX()), clientPos.getY(), MathHelper.floor(virtual.localZ())));
    }

    private static Vec3d translateToCell(ServerPlayerEntity player, CellPos target, Vec3d clientPos) {
        CellPos origin = CellPacketRouting.origin(player);
        return new Vec3d(
                clientPos.x - ((double) target.x() - (double) origin.x())
                        * (double) VirtualPosition.CELL_SIZE,
                clientPos.y,
                clientPos.z - ((double) target.z() - (double) origin.z())
                        * (double) VirtualPosition.CELL_SIZE);
    }

    private static boolean isBlockAction(PlayerActionC2SPacket.Action action) {
        return action == PlayerActionC2SPacket.Action.START_DESTROY_BLOCK
                || action == PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK
                || action == PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK;
    }

    /**
     * Vanilla successful mining has no direct acknowledgement packet and relies
     * on the current world's chunk watcher. A neighboring cell is watched by our
     * shadow tracker, so send the authoritative result explicitly. Neighbors are
     * included because breaking one half of a double chest changes the other half.
     */
    private static void sendAuthoritativeBlockNeighborhood(
            ServerPlayerEntity player, ServerWorld world, BlockPos pos) {
        sendAuthoritativeBlock(player, world, pos);
        for (Direction direction : Direction.values()) {
            sendAuthoritativeBlock(player, world, pos.offset(direction));
        }
    }

    private static void sendAuthoritativeBlock(
            ServerPlayerEntity player, ServerWorld source, BlockPos sourcePos) {
        var resolved = CellBoundaryAccess.resolveLoadedBlock(source, sourcePos);
        if (resolved.isPresent()) {
            CellBoundaryAccess.ResolvedBlock target = resolved.get();
            CellPacketRouting.sendFrom(player, target.world(),
                    new BlockUpdateS2CPacket(
                            target.pos(), target.world().getBlockState(target.pos())));
            return;
        }
        CellPacketRouting.sendFrom(player, source,
                new BlockUpdateS2CPacket(sourcePos, source.getBlockState(sourcePos)));
    }

    private static void runInWorld(ServerPlayerEntity player, ServerWorld targetWorld, Runnable action) {
        ServerWorld originalWorld = player.getEntityWorld();
        Vec3d originalPosition = player.getEntityPos();
        CellPos originalCell = CellWorldKey.cell(originalWorld.getRegistryKey());
        CellPos targetCell = CellWorldKey.cell(targetWorld.getRegistryKey());
        Vec3d projectedPosition = new Vec3d(
                originalPosition.x + ((double) originalCell.x() - (double) targetCell.x())
                        * (double) VirtualPosition.CELL_SIZE,
                originalPosition.y,
                originalPosition.z + ((double) originalCell.z() - (double) targetCell.z())
                        * (double) VirtualPosition.CELL_SIZE);
        boolean previous = REROUTING.get();
        REROUTING.set(true);
        ServerPlayerInteractionManager interactionManager = player.interactionManager;
        player.setServerWorld(targetWorld);
        player.setPosition(projectedPosition);
        interactionManager.setWorld(targetWorld);
        try {
            CellPacketRouting.withSource(targetWorld, action);
        } finally {
            interactionManager.setWorld(originalWorld);
            player.setServerWorld(originalWorld);
            player.setPosition(originalPosition);
            REROUTING.set(previous);
        }
    }

    private record BlockTarget(CellPos cell, ServerWorld world, BlockPos pos) {
    }

    private record RemoteMining(ServerWorld world, BlockPos pos) {
    }

    private record RemoteScreen(ServerWorld world, ScreenHandler handler) {
    }
}
