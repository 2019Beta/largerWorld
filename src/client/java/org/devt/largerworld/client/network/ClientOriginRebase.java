package org.devt.largerworld.client.network;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientChunkManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.PositionInterpolator;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;
import org.devt.largerworld.coordinate.VirtualPosition;
import org.devt.largerworld.mixin.EntityAccessor;
import org.devt.largerworld.mixin.client.ClientWorldOriginAccessor;
import org.devt.largerworld.mixin.client.BlockEntityOriginAccessor;
import org.devt.largerworld.network.CellPacketPayload;
import org.devt.largerworld.network.OriginRebasePayload;
import java.util.ArrayList;
import java.util.List;

/** Migrates loaded data on the client thread without replacing ClientWorld/player. */
public final class ClientOriginRebase {
    private ClientOriginRebase() {
    }

    public static void apply(MinecraftClient client, OriginRebasePayload payload) {
        ClientWorld world = client.world;
        if (world == null || client.player == null || client.getNetworkHandler() == null) {
            ClientCellPacketContext.setConnectionOrigin(payload.next());
            return;
        }
        if (!ClientCellPacketContext.connectionOrigin(payload.previous()).equals(payload.previous())) {
            throw new IllegalStateException("Out-of-order client origin rebase");
        }
        ClientWorldOriginAccessor access = (ClientWorldOriginAccessor) world;
        // Resolve predictions and queued light/chunk work in their original
        // coordinate frame before taking a coherent in-memory snapshot.
        drain(access);
        access.largerworld$pendingUpdates().processPendingUpdates(Integer.MAX_VALUE, world);
        ClientChunkManager oldManager = world.getChunkManager();
        oldManager.getLightingProvider().doLightUpdates();
        ClientChunkSnapshot snapshot = (ClientChunkSnapshot) oldManager;
        List<WorldChunk> loaded = snapshot.largerworld$loadedChunks();
        ChunkPos oldCenter = snapshot.largerworld$mapCenter();

        boolean canTranslate = payload.previous().isWithin(payload.next(), 64);
        long shiftX = canTranslate ? payload.previous().deltaXExact(payload.next()) * VirtualPosition.CELL_SIZE : 0;
        long shiftZ = canTranslate ? payload.previous().deltaZExact(payload.next()) * VirtualPosition.CELL_SIZE : 0;
        long centerX = oldCenter.x + (shiftX >> 4);
        long centerZ = oldCenter.z + (shiftZ >> 4);
        long margin = (long) (Math.max(2, snapshot.largerworld$loadDistance()) + 4) * 16;
        canTranslate &= Math.abs(centerX * 16) + margin < World.HORIZONTAL_LIMIT
                && Math.abs(centerZ * 16) + margin < World.HORIZONTAL_LIMIT;
        List<ChunkDataS2CPacket> packets = new ArrayList<>();
        List<BlockEntity> previousBlockEntities = new ArrayList<>();
        if (canTranslate) {
            for (WorldChunk chunk : loaded) {
                packets.add(new ChunkDataS2CPacket(chunk, oldManager.getLightingProvider(), null, null));
                previousBlockEntities.addAll(chunk.getBlockEntities().values());
            }
        }
        // No server round trip: copied section data, block entities and light
        // arrays are immediately reapplied at their translated chunk positions.
        for (WorldChunk chunk : loaded) {
            oldManager.unload(chunk.getPos());
        }
        // Editors may still hold a block-entity reference from the old chunk.
        // Its coordinate must move too, even though packet replay builds the
        // replacement chunk's own block-entity instance.
        for (BlockEntity entity : previousBlockEntities) {
            ((BlockEntityOriginAccessor) entity).largerworld$setOriginPosition(
                    entity.getPos().add((int) shiftX, 0, (int) shiftZ));
        }
        ClientChunkManager replacement = new ClientChunkManager(world, snapshot.largerworld$loadDistance());
        replacement.setChunkMapCenter(canTranslate ? (int) centerX : 0, canTranslate ? (int) centerZ : 0);
        access.largerworld$setChunkManager(replacement);
        ClientCellPacketContext.setConnectionOrigin(payload.next());

        List<Entity> entities = new ArrayList<>();
        world.getEntities().forEach(entities::add);
        if (!entities.contains(client.player)) {
            entities.add(client.player);
        }
        for (Entity entity : entities) {
            if (canTranslate) {
                shift(entity, new Vec3d(shiftX, 0, shiftZ));
            } else if (entity == client.player) {
                // The following authoritative teleport positions the same
                // player object at its destination in this newly empty window.
                shift(entity, new Vec3d(-entity.getX(), 0, -entity.getZ()));
            } else {
                world.removeEntity(entity.getId(), Entity.RemovalReason.UNLOADED_TO_CHUNK);
            }
        }
        if (canTranslate) {
            world.getWorldBorder().setCenter(world.getWorldBorder().getCenterX() + shiftX,
                    world.getWorldBorder().getCenterZ() + shiftZ);
        } else {
            ClientEntityHandoff.clear();
            ClientContinuousEntityHandoff.tick(null);
            client.setCameraEntity(client.player);
            client.player.closeHandledScreen();
        }
        client.particleManager.clearParticles();
        client.crosshairTarget = null;
        client.targetedEntity = null;
        client.worldRenderer.reload();
        for (ChunkDataS2CPacket packet : packets) {
            ClientCellPacketContext.apply(new CellPacketPayload(payload.previous(), payload.next(), packet),
                    client.getNetworkHandler());
        }
        drain(access);
        replacement.getLightingProvider().doLightUpdates();
        client.worldRenderer.scheduleTerrainUpdate();
    }

    private static void drain(ClientWorldOriginAccessor access) {
        Runnable update;
        while ((update = access.largerworld$chunkUpdates().poll()) != null) {
            update.run();
        }
    }

    private static void shift(Entity entity, Vec3d delta) {
        PositionInterpolator interpolator = entity.getInterpolator();
        boolean interpolating = interpolator != null && interpolator.isInterpolating();
        Vec3d target = interpolating ? interpolator.getLerpedPos().add(delta) : null;
        float yaw = interpolating ? interpolator.getLerpedYaw() : entity.getYaw();
        float pitch = interpolating ? interpolator.getLerpedPitch() : entity.getPitch();
        Vec3d tracked = entity.getTrackedPosition().getPos().add(delta);
        Vec3d oldLast = ((EntityAccessor) entity).largerworld$getLastPos();
        Vec3d last = (oldLast == null ? entity.getEntityPos() : oldLast).add(delta);
        entity.setPosition(entity.getEntityPos().add(delta));
        entity.lastX += delta.x;
        entity.lastZ += delta.z;
        entity.lastRenderX += delta.x;
        entity.lastRenderZ += delta.z;
        ((EntityAccessor) entity).largerworld$setLastPos(last);
        entity.getTrackedPosition().setPos(tracked);
        entity.supportingBlockPos = entity.supportingBlockPos.map(pos -> pos.add((int) delta.x, 0, (int) delta.z));
        if (interpolating) {
            interpolator.refreshPositionAndAngles(target, yaw, pitch);
        }
        if (entity instanceof ClientPlayerOriginState state) {
            state.largerworld$shiftLastSentPosition(delta.x, delta.z);
        }
    }
}
