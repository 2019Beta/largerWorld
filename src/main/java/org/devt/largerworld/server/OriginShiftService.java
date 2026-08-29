package org.devt.largerworld.server;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.CamelEntity;
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
import org.devt.largerworld.network.ContinuousEntityHandoffPayload;
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
    private static final Map<UUID, RidingGraphSnapshot> LAST_RIDING_GRAPHS =
            new HashMap<>();
    private static final Map<UUID, UUID> LAST_MEMBER_ROOTS = new HashMap<>();

    private OriginShiftService() {
    }

    public static void tick(MinecraftServer server) {
        if (server.getTicks() % 20 == 0) {
            preloadApproachingCells(server);
        }

        Set<UUID> handledRoots = new HashSet<>();
        Set<UUID> seenMembers = new HashSet<>();
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
                RidingGraphSnapshot graph = RidingGraphSnapshot.capture(root);
                seenMembers.addAll(graph.members());
                RidingGraphSnapshot previous = LAST_RIDING_GRAPHS.put(root.getUuid(), graph);
                boolean membershipChanged = previous == null
                        ? graph.members().size() > 1
                        : !previous.members().equals(graph.members());
                boolean rootChanged = graph.members().stream().anyMatch(member -> {
                    UUID previousRoot = LAST_MEMBER_ROOTS.get(member);
                    return previousRoot != null && !previousRoot.equals(root.getUuid());
                });
                for (UUID member : graph.members()) {
                    LAST_MEMBER_ROOTS.put(member, root.getUuid());
                }

                boolean playerGraphChanged = membershipChanged || rootChanged;
                boolean involvedPlayer = graph.containsPlayer()
                        || previous != null && previous.containsPlayer();
                if (playerGraphChanged && involvedPlayer && isOutsideCell(root)) {
                    // Mount/dismount and boundary tracking can occur in the same
                    // server tick. Let the graph stabilize for one complete tick
                    // so it cannot start as an ordinary entity handoff and finish
                    // as a player vehicle transaction.
                    Largerworld.LOGGER.info(
                            "[cell-handoff-server] DEFER_RIDING_GRAPH root={} id={} "
                                    + "members={} previousMembers={}",
                            root.getUuid(), root.getId(), graph.members(),
                            previous == null ? null : previous.members());
                    continue;
                }
                shiftIfNeeded(root);
            }
        }
        LAST_RIDING_GRAPHS.keySet().retainAll(handledRoots);
        LAST_MEMBER_ROOTS.keySet().retainAll(seenMembers);
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
        boolean playerControlledGraph = continuousMovement
                && sourceMembers.stream().anyMatch(
                member -> member instanceof ServerPlayerEntity player
                        && player.hasVehicle());
        boolean graphContainsPlayer = sourceMembers.stream()
                .anyMatch(ServerPlayerEntity.class::isInstance);
        // A ridden graph keeps the existing client objects and ignores its
        // duplicate destination spawns. Other continuously moving, non-player
        // graphs keep their client objects too, but consume the authoritative
        // destination spawn into those same objects.
        boolean preserveClientIdentity = playerControlledGraph;
        boolean preserveContinuousEntity = continuousMovement
                && !graphContainsPlayer;
        boolean protectSourceClientEntity =
                preserveClientIdentity || preserveContinuousEntity;
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
        Largerworld.LOGGER.info(
                "[cross-velocity] SNAPSHOT type={} id={} uuid={} source={} target={} vel={} "
                        + "continuous={} identity={} continuousEntity={}",
                root.getType(), root.getId(), root.getUuid(),
                CellWorldKey.cell(sourceWorld.getRegistryKey()), targetCell,
                root.getVelocity(), continuousMovement, preserveClientIdentity,
                preserveContinuousEntity);
        sourceMembers.forEach(member -> debugCamel("BEFORE_TELEPORT", member));

        if (protectSourceClientEntity) {
            // Retire the source tracker without creating a client-visible gap.
            // Both modes retain the object. A normal continuous entity later
            // consumes its authoritative target spawn into that same object.
            protectSourceTrackerListeners(sourceWorld, sourceMembers);
        }
        if (preserveClientIdentity) {
            sendClientHandoff(
                    EntityHandoffPayload.Phase.BEGIN,
                    sourceWorld,
                    targetCell,
                    sourceMembers);
        } else if (preserveContinuousEntity) {
            sendContinuousEntityHandoff(
                    ContinuousEntityHandoffPayload.Phase.BEGIN,
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
            if (protectSourceClientEntity) {
                abortSourceTrackerProtection(sourceWorld, sourceMembers);
            }
            if (preserveContinuousEntity) {
                sendContinuousEntityHandoff(
                        ContinuousEntityHandoffPayload.Phase.ABORT,
                        sourceWorld,
                        targetCell,
                        sourceMembers);
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
        Largerworld.LOGGER.info(
                "[cross-velocity] AFTER_TELEPORT type={} id={} uuid={} vel={}",
                teleportedRoot.getType(), teleportedRoot.getId(),
                teleportedRoot.getUuid(), teleportedRoot.getVelocity());
        List<Entity> targetMembers = teleportedRoot.streamSelfAndPassengers().toList();
        if (continuousMovement) {
            targetMembers.stream()
                    .filter(CamelEntity.class::isInstance)
                    .map(CamelEntity.class::cast)
                    .forEach(CamelHandoffGrace::mark);
        }
        targetMembers.forEach(member -> debugCamel("AFTER_TELEPORT", member));

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
            Largerworld.LOGGER.info(
                    "[cross-velocity] AFTER_RESTORE type={} id={} uuid={} vel={} dirty={}",
                    teleportedRoot.getType(), teleportedRoot.getId(),
                    teleportedRoot.getUuid(), teleportedRoot.getVelocity(),
                    teleportedRoot.velocityDirty);
        }
        targetMembers.forEach(member -> debugCamel("AFTER_RESTORE", member));

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

    private static boolean isOutsideCell(Entity root) {
        ServerWorld currentWorld = (ServerWorld) root.getEntityWorld();
        CellPos currentCell = CellWorldKey.cell(currentWorld.getRegistryKey());
        return !VirtualPosition.normalize(
                currentCell, root.getX(), root.getY(), root.getZ())
                .isInCell(currentCell);
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
                        "[cell-handoff-server] MARKER phase={} type={} id={} uuid={} graphRecipients={}",
                        phase, member.getType(), member.getId(), member.getUuid(),
                        graphPlayers.stream().map(player -> player.getUuid().toString()).toList());
            }
            if (member instanceof ServerPlayerEntity) {
                // The graph-recipient send above already covers the player
                // itself; use vanilla's excluding variant to avoid delivering
                // duplicate BEGIN/COMMIT markers to that same connection.
                sendingWorld.getChunkManager().sendToOtherNearbyPlayers(member, packet);
            } else {
                sendingWorld.getChunkManager().sendToNearbyPlayers(member, packet);
            }
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

    private static void sendContinuousEntityHandoff(
            ContinuousEntityHandoffPayload.Phase phase,
            ServerWorld sourceWorld,
            CellPos targetCell,
            List<Entity> members) {
        CellPos sourceCell = CellWorldKey.cell(sourceWorld.getRegistryKey());
        for (Entity member : members) {
            var packet = new CustomPayloadS2CPacket(new ContinuousEntityHandoffPayload(
                    phase,
                    member.getId(),
                    member.getUuid(),
                    sourceCell,
                    targetCell));
            Largerworld.LOGGER.info(
                    "[continuous-handoff-server] MARKER phase={} type={} id={} uuid={} "
                            + "source={} target={}",
                    phase, member.getType(), member.getId(), member.getUuid(),
                    sourceCell, targetCell);
            sourceWorld.getChunkManager().sendToNearbyPlayers(member, packet);
        }
    }

    private static void debugCamel(String phase, Entity entity) {
        if (!(entity instanceof CamelEntity camel)) {
            return;
        }
        Largerworld.LOGGER.info(
                "[cross-camel] phase={} id={} worldTime={} pose={} sitting={} "
                        + "visualSitting={} changing={} lastPoseTick={} poseTime={} passengers={}",
                phase, camel.getId(), camel.getEntityWorld().getTime(), camel.getPose(),
                camel.isSitting(), camel.shouldUpdateSittingAnimations(),
                camel.isChangingPose(),
                camel.getDataTracker().get(CamelEntity.LAST_POSE_TICK),
                camel.getTimeSinceLastPoseTick(), camel.getPassengerList().size());
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

    private record RidingGraphSnapshot(Set<UUID> members, boolean containsPlayer) {
        private static RidingGraphSnapshot capture(Entity root) {
            Set<UUID> members = new HashSet<>();
            boolean containsPlayer = false;
            for (Entity member : root.streamSelfAndPassengers().toList()) {
                members.add(member.getUuid());
                containsPlayer |= member instanceof ServerPlayerEntity;
            }
            return new RidingGraphSnapshot(Set.copyOf(members), containsPlayer);
        }
    }

}
