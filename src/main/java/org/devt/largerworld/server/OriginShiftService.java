package org.devt.largerworld.server;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.network.packet.s2c.play.EntityPassengersSetS2CPacket;
import net.minecraft.network.packet.s2c.common.CustomPayloadS2CPacket;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.TeleportTarget;
import org.devt.largerworld.Largerworld;
import org.devt.largerworld.coordinate.CellPos;
import org.devt.largerworld.coordinate.VirtualPosition;
import org.devt.largerworld.world.CellWorldKey;
import org.devt.largerworld.world.CellWorldManager;
import org.devt.largerworld.mixin.ServerChunkLoadingManagerAccessor;
import org.devt.largerworld.mixin.ServerPlayNetworkHandlerAccessor;
import org.devt.largerworld.network.EntityHandoffPayload;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Migrates complete entity/passenger graphs between independently stored cell worlds. */
public final class OriginShiftService {
    private OriginShiftService() {
    }

    public static void tick(MinecraftServer server) {
        if (server.getTicks() % 20 == 0) {
            preloadApproachingCells(server);
        }

        Set<UUID> handledRoots = new HashSet<>();
        ArrayList<ServerWorld> worldSnapshot = new ArrayList<>();
        server.getWorlds().forEach(worldSnapshot::add);
        for (ServerWorld world : worldSnapshot) {
            ArrayList<Entity> entitySnapshot = new ArrayList<>();
            world.iterateEntities().forEach(entitySnapshot::add);
            for (Entity entity : entitySnapshot) {
                if (entity.isRemoved()) {
                    continue;
                }
                Entity root = entity.getRootVehicle();
                if (!handledRoots.add(root.getUuid())) {
                    continue;
                }
                shiftIfNeeded(root);
            }
        }
    }

    private static void preloadApproachingCells(MinecraftServer server) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            ServerWorld currentWorld = player.getEntityWorld();
            CellPos currentCell = CellWorldKey.cell(currentWorld.getRegistryKey());
            int margin = (player.getViewDistance() + 2) * 16;
            int deltaX = player.getX() >= VirtualPosition.HALF_CELL - margin ? 1
                    : player.getX() < -VirtualPosition.HALF_CELL + margin ? -1 : 0;
            int deltaZ = player.getZ() >= VirtualPosition.HALF_CELL - margin ? 1
                    : player.getZ() < -VirtualPosition.HALF_CELL + margin ? -1 : 0;

            if (deltaX != 0) {
                preload(server, currentWorld, currentCell, deltaX, 0, player.getZ());
            }
            if (deltaZ != 0) {
                preload(server, currentWorld, currentCell, 0, deltaZ, player.getX());
            }
            if (deltaX != 0 && deltaZ != 0) {
                preload(server, currentWorld, currentCell, deltaX, deltaZ, 0);
            }
        }
    }

    private static void preload(
            MinecraftServer server,
            ServerWorld currentWorld,
            CellPos currentCell,
            int deltaX,
            int deltaZ,
            double unchangedAxis) {
        CellPos targetCell = currentCell.add(deltaX, deltaZ);
        ServerWorld target = CellWorldManager.getOrCreate(
                server, CellWorldKey.baseWorld(currentWorld.getRegistryKey()), targetCell);
        double x = deltaX > 0 ? -VirtualPosition.HALF_CELL + 1
                : deltaX < 0 ? VirtualPosition.HALF_CELL - 1 : unchangedAxis;
        double z = deltaZ > 0 ? -VirtualPosition.HALF_CELL + 1
                : deltaZ < 0 ? VirtualPosition.HALF_CELL - 1 : unchangedAxis;
        ChunkPos entryChunk = new ChunkPos(MathHelper.floor(x) >> 4, MathHelper.floor(z) >> 4);
        target.getChunkManager().addTicket(ChunkTicketType.PORTAL, entryChunk, 3);
    }

    public static void reconcilePlayerWorlds(MinecraftServer server) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            ServerWorld currentWorld = player.getEntityWorld();
            CellPos worldCell = CellWorldKey.cell(currentWorld.getRegistryKey());
            CellPos attachedCell = player.getAttachedOrCreate(Largerworld.CELL_POS);

            if (!worldCell.equals(CellPos.ZERO)) {
                if (!worldCell.equals(attachedCell)) {
                    player.setAttached(Largerworld.CELL_POS, worldCell);
                }
            } else if (!attachedCell.equals(CellPos.ZERO)) {
                ServerWorld target = CellWorldManager.getOrCreate(
                        server, CellWorldKey.baseWorld(currentWorld.getRegistryKey()), attachedCell);
                teleportGraph(player.getRootVehicle(), target,
                        player.getRootVehicle().getX(), player.getRootVehicle().getY(), player.getRootVehicle().getZ(),
                        attachedCell);
            }
        }
    }

    public static boolean shiftIfNeeded(Entity root) {
        ServerWorld currentWorld = (ServerWorld) root.getEntityWorld();
        CellPos currentCell = CellWorldKey.cell(currentWorld.getRegistryKey());
        VirtualPosition normalized = VirtualPosition.normalize(
                currentCell, root.getX(), root.getY(), root.getZ());

        if (normalized.isInCell(currentCell)) {
            return false;
        }

        ServerWorld targetWorld = CellWorldManager.getOrCreate(
                currentWorld.getServer(),
                CellWorldKey.baseWorld(currentWorld.getRegistryKey()),
                normalized.cell());
        return teleportGraph(root, targetWorld,
                normalized.localX(), normalized.y(), normalized.localZ(), normalized.cell(), true);
    }

    public static boolean teleportGraph(
            Entity root, ServerWorld targetWorld, double x, double y, double z, CellPos targetCell) {
        return teleportGraph(root, targetWorld, x, y, z, targetCell, false);
    }

    private static boolean teleportGraph(
            Entity root,
            ServerWorld targetWorld,
            double x,
            double y,
            double z,
            CellPos targetCell,
            boolean continuousMovement) {
        if (!(root.getEntityWorld() instanceof ServerWorld sourceWorld)) {
            return false;
        }
        List<Entity> sourceMembers = root.streamSelfAndPassengers().toList();
        // Every natural cell crossing is one continuous client-side entity
        // lifecycle. Commands and other explicit teleports still use vanilla's
        // destroy/spawn lifecycle because continuousMovement is false there.
        boolean preserveClientIdentity = continuousMovement;
        boolean playerControlledGraph = continuousMovement
                && sourceMembers.stream().anyMatch(
                member -> member instanceof ServerPlayerEntity player
                        && player.hasVehicle());
        Map<UUID, Vec3d> velocities = new HashMap<>();
        Map<UUID, UUID> mobTargetIds = new HashMap<>();
        Map<UUID, LivingEntity> mobTargetReferences = new HashMap<>();
        for (Entity member : sourceMembers) {
            velocities.put(member.getUuid(), member.getVelocity());
            if (member instanceof MobEntity mob && mob.getTarget() != null) {
                LivingEntity target = mob.getTarget();
                mobTargetIds.put(member.getUuid(), target.getUuid());
                mobTargetReferences.put(member.getUuid(), target);
            }
            if (member instanceof ServerPlayerEntity player) {
                // Capture the old connection origin before changing the logical
                // world. A distant teleport may need to rebase it.
                CellPacketRouting.origin(player);
            }
        }

        if (preserveClientIdentity) {
            // Install both halves of the identity guard before changing any
            // tracker ownership: mark source listeners for silent retirement,
            // then tell their clients to retain the matching id/UUID.
            protectSourceTrackerListeners(sourceWorld, sourceMembers);
            sendClientHandoff(
                    EntityHandoffPayload.Phase.BEGIN,
                    sourceWorld,
                    targetCell,
                    sourceMembers);
        }

        // Claim the source view before vanilla starts moving the root. Waiting
        // for the passenger teleport is too late because the old root tracker
        // has already been stopped by then.
        if (continuousMovement) {
            for (Entity member : sourceMembers) {
                if (member instanceof ServerPlayerEntity player) {
                    CellViewTracker.prepareTransition(
                            sourceWorld.getServer(), player, targetCell,
                            x + player.getX() - root.getX(),
                            z + player.getZ() - root.getZ());
                }
            }
        }

        TeleportTarget target = new TeleportTarget(
                targetWorld,
                new Vec3d(x, y, z),
                root.getVelocity(),
                root.getYaw(),
                root.getPitch(),
                TeleportTarget.NO_OP);
        Entity teleportedRoot = CellPacketRouting.withSourceResult(targetWorld, () ->
                SeamlessCellTeleport.withCellHandoff(
                        continuousMovement, () -> root.teleportTo(target)));
        if (teleportedRoot == null) {
            if (preserveClientIdentity) {
                abortSourceTrackerProtection(sourceWorld, sourceMembers);
            }
            if (continuousMovement) {
                for (Entity member : sourceMembers) {
                    if (member instanceof ServerPlayerEntity player) {
                        CellViewTracker.abortTransition(player);
                    }
                }
            }
            Largerworld.LOGGER.warn("Failed to move entity graph {} to cell {}",
                    root.getUuid(), targetCell);
            return false;
        }
        List<Entity> targetMembers = teleportedRoot.streamSelfAndPassengers().toList();

        // Update the synchronized logical cell only after teleportTo has rebased
        // the network origin and completed the vanilla world change. Setting it
        // before teleport let persistent state report the destination while the
        // player was still in the source world, so transition packets could be
        // tagged as destination-cell data (or discarded as outside the old
        // client window). On an integrated client that left stale source chunks
        // mixed into the freshly loaded destination terrain.
        CellPacketRouting.withSource(targetWorld, () -> {
            for (Entity member : targetMembers) {
                if (member instanceof ServerPlayerEntity player) {
                    player.setAttached(Largerworld.CELL_POS, targetCell);
                }
            }
        });

        // Cross-world teleportation recreates ordinary entities and vehicles,
        // preserving their complete serialized state (inventory, saddle,
        // ownership and subtype data). UUID lookup reconnects the rebuilt graph.
        Map<UUID, Entity> rebuiltMembers = new HashMap<>();
        for (Entity member : targetMembers) {
            rebuiltMembers.put(member.getUuid(), member);
        }

        // Recreated mobs lose their live attack target. Restore it after the
        // complete graph exists so pursuit can continue across the seam.
        for (Map.Entry<UUID, UUID> entry : mobTargetIds.entrySet()) {
            Entity rebuilt = rebuiltMembers.get(entry.getKey());
            if (!(rebuilt instanceof MobEntity mob)) {
                continue;
            }
            Entity graphTarget = rebuiltMembers.get(entry.getValue());
            LivingEntity restoredTarget = graphTarget instanceof LivingEntity living
                    ? living
                    : mobTargetReferences.get(entry.getKey());
            if (restoredTarget != null && !restoredTarget.isRemoved()) {
                mob.setTarget(restoredTarget);
            }
        }

        // Cross-world teleportation dismantles the riding graph, moves every
        // passenger independently, recreates the root and only then reattaches
        // the passengers. Reassert the snapshot after that complete sequence;
        // restoring velocity only from TeleportTarget/copyFrom is too early.
        for (Entity member : targetMembers) {
            if (member == teleportedRoot) {
                continue;
            }
            Vec3d velocity = velocities.get(member.getUuid());
            if (velocity != null) {
                member.setVelocity(velocity);
                if (!(member instanceof ServerPlayerEntity)) {
                    member.velocityDirty = true;
                }
            }
        }
        Vec3d rootVelocity = velocities.get(teleportedRoot.getUuid());
        if (rootVelocity != null) {
            teleportedRoot.setVelocity(rootVelocity);
            // The controlling client keeps the same vehicle object and its
            // local momentum through EntityHandoffPayload. A forced velocity
            // packet would rewind that client prediction; other roots need the
            // ordinary authoritative velocity update.
            if (!(teleportedRoot instanceof ServerPlayerEntity)
                    && !playerControlledGraph) {
                teleportedRoot.velocityDirty = true;
            }
        }

        // VehicleMove validation keeps both an object reference and local-cell
        // coordinates from the previous tick. Rebase them immediately; waiting
        // for the next network-handler tick rejects or corrects the first input
        // packet after the seam and produces a visible one-tick pause.
        for (Entity member : targetMembers) {
            if (!(member instanceof ServerPlayerEntity player) || !player.hasVehicle()) {
                continue;
            }
            Entity vehicle = player.getRootVehicle();
            ServerPlayNetworkHandlerAccessor handler =
                    (ServerPlayNetworkHandlerAccessor) player.networkHandler;
            handler.largerworld$setTopmostRiddenEntity(vehicle);
            handler.largerworld$setLastTickRiddenX(vehicle.getX());
            handler.largerworld$setLastTickRiddenY(vehicle.getY());
            handler.largerworld$setLastTickRiddenZ(vehicle.getZ());
            handler.largerworld$setUpdatedRiddenX(vehicle.getX());
            handler.largerworld$setUpdatedRiddenY(vehicle.getY());
            handler.largerworld$setUpdatedRiddenZ(vehicle.getZ());
            handler.largerworld$setVehicleFloating(false);
            handler.largerworld$setVehicleFloatingTicks(0);
        }

        if (preserveClientIdentity) {
            // The replacement entity and any riding graph are now final.
            // COMMIT transfers tracker authority even when shadow ownership
            // changes without a replacement spawn on the client.
            sendClientHandoff(
                    EntityHandoffPayload.Phase.COMMIT,
                    targetWorld,
                    CellWorldKey.cell(sourceWorld.getRegistryKey()),
                    targetMembers);
        }

        // Send a final relation for every vehicle in the graph, not only the
        // root, so nested mounts are committed as one coherent snapshot. These
        // packets are deliberately ordered after COMMIT on each connection.
        for (Entity vehicle : targetMembers) {
            if (!vehicle.hasPassengers()) {
                continue;
            }
            EntityPassengersSetS2CPacket passengers =
                    new EntityPassengersSetS2CPacket(vehicle);
            CellPacketRouting.withSource(targetWorld, () ->
                    targetWorld.getChunkManager()
                            .sendToNearbyPlayers(vehicle, passengers));
            CellViewTracker.sendToShadowPlayers(
                    targetWorld,
                    null,
                    vehicle.getX(),
                    vehicle.getY(),
                    vehicle.getZ(),
                    256.0,
                    passengers);
        }
        return true;
    }

    private static void sendClientHandoff(
            EntityHandoffPayload.Phase phase,
            ServerWorld sendingWorld,
            CellPos otherCell,
            List<Entity> members) {
        CellPos sendingCell = CellWorldKey.cell(sendingWorld.getRegistryKey());
        CellPos sourceCell = phase == EntityHandoffPayload.Phase.BEGIN
                ? sendingCell : otherCell;
        CellPos targetCell = phase == EntityHandoffPayload.Phase.BEGIN
                ? otherCell : sendingCell;
        List<ServerPlayerEntity> graphPlayers = members.stream()
                .filter(ServerPlayerEntity.class::isInstance)
                .map(ServerPlayerEntity.class::cast)
                .toList();
        for (Entity member : members) {
            var packet = new CustomPayloadS2CPacket(new EntityHandoffPayload(
                    phase,
                    member.getId(),
                    member.getUuid(),
                    sourceCell,
                    targetCell));

            // A rider must receive the vehicle marker even while the source
            // tracker itself is being torn down. The normal tracked/view send
            // below covers other observers; duplicate markers are idempotent.
            for (ServerPlayerEntity player : graphPlayers) {
                player.networkHandler.sendPacket(packet);
            }
            if (!graphPlayers.isEmpty()) {
                Largerworld.LOGGER.info(
                        "[cell-handoff-server] MARKER phase={} id={} uuid={} graphRecipients={}",
                        phase, member.getId(), member.getUuid(),
                        graphPlayers.stream().map(player -> player.getUuid().toString()).toList());
            }
            sendingWorld.getChunkManager().sendToNearbyPlayers(member, packet);
            if (phase == EntityHandoffPayload.Phase.COMMIT) {
                CellViewTracker.sendToShadowPlayers(
                        sendingWorld,
                        null,
                        member.getX(),
                        member.getY(),
                        member.getZ(),
                        256.0,
                        packet);
            }
        }
    }

    private static void protectSourceTrackerListeners(
            ServerWorld sourceWorld, List<Entity> members) {
        sourceTrackers(sourceWorld, members).forEach(
                CellEntityTracker::largerworld$beginHandoffTracking);
    }

    private static void abortSourceTrackerProtection(
            ServerWorld sourceWorld, List<Entity> members) {
        sourceTrackers(sourceWorld, members).forEach(
                CellEntityTracker::largerworld$abortHandoffTracking);
    }

    private static List<CellEntityTracker> sourceTrackers(
            ServerWorld sourceWorld, List<Entity> members) {
        var trackers = ((ServerChunkLoadingManagerAccessor)
                sourceWorld.getChunkManager().chunkLoadingManager)
                .largerworld$getEntityTrackers();
        List<CellEntityTracker> result = new ArrayList<>();
        for (Entity member : members) {
            Object value = trackers.get(member.getId());
            if (!(value instanceof CellEntityTracker tracker)) {
                continue;
            }
            Entity tracked = tracker.largerworld$getEntity();
            if (tracked.getUuid().equals(member.getUuid())) {
                result.add(tracker);
            }
        }
        return result;
    }

}
