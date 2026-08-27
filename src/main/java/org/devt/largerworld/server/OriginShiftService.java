package org.devt.largerworld.server;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
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
import org.devt.largerworld.mixin.ServerPlayNetworkHandlerAccessor;
import org.devt.largerworld.network.EntityHandoffPayload;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Iterator;
import java.util.List;

/** Migrates complete entity/passenger graphs between independently stored cell worlds. */
public final class OriginShiftService {
    private static final Map<UUID, DebugProbe> DEBUG_PROBES = new HashMap<>();

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
        tickDebugProbes();
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
        boolean debugTransition = continuousMovement && root.hasPassengers();
        if (debugTransition) {
            logGraph("BEGIN", root);
        }
        Map<UUID, Vec3d> velocities = new HashMap<>();
        Map<UUID, UUID> mobTargetIds = new HashMap<>();
        Map<UUID, LivingEntity> mobTargetReferences = new HashMap<>();
        for (Entity member : root.streamSelfAndPassengers().toList()) {
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

        TeleportTarget target = new TeleportTarget(
                targetWorld,
                new Vec3d(x, y, z),
                root.getVelocity(),
                root.getYaw(),
                root.getPitch(),
                TeleportTarget.NO_OP);
        Entity[] result = new Entity[1];
        List<ServerPlayerEntity> handoffPlayers = continuousMovement && root.hasPassengers()
                ? root.streamSelfAndPassengers()
                        .filter(member -> member instanceof ServerPlayerEntity player
                                && player.hasVehicle())
                        .map(member -> (ServerPlayerEntity) member)
                        .toList()
                : List.of();
        for (ServerPlayerEntity player : handoffPlayers) {
            CellPacketRouting.sendFrom(
                    player,
                    sourceWorld,
                    new CustomPayloadS2CPacket(
                            new EntityHandoffPayload(root.getId(), true)));
        }
        CellPacketRouting.withSource(targetWorld, () -> result[0] = continuousMovement
                ? SeamlessCellTeleport.withContinuousMovement(() -> root.teleportTo(target))
                : root.teleportTo(target));
        Entity teleportedRoot = result[0];
        if (teleportedRoot == null) {
            for (ServerPlayerEntity player : handoffPlayers) {
                CellPacketRouting.sendFrom(
                        player,
                        player.getEntityWorld(),
                        new CustomPayloadS2CPacket(
                                new EntityHandoffPayload(root.getId(), false)));
            }
            if (debugTransition) {
                Largerworld.LOGGER.warn("[cell-transition] FAILED rootUuid={} targetCell={}",
                        root.getUuid(), targetCell);
            }
            return false;
        }

        // Update the synchronized logical cell only after teleportTo has rebased
        // the network origin and completed the vanilla world change. Setting it
        // before teleport let persistent state report the destination while the
        // player was still in the source world, so transition packets could be
        // tagged as destination-cell data (or discarded as outside the old
        // client window). On an integrated client that left stale source chunks
        // mixed into the freshly loaded destination terrain.
        CellPacketRouting.withSource(targetWorld, () -> {
            for (Entity member : teleportedRoot.streamSelfAndPassengers().toList()) {
                if (member instanceof ServerPlayerEntity player) {
                    player.setAttached(Largerworld.CELL_POS, targetCell);
                }
            }
        });

        // Vanilla may recreate regular entities and independently teleport their
        // passengers. Restore every member after the complete graph has been
        // rebuilt so those intermediate operations cannot discard momentum.
        Map<UUID, Entity> rebuiltMembers = new HashMap<>();
        for (Entity member : teleportedRoot.streamSelfAndPassengers().toList()) {
            rebuiltMembers.put(member.getUuid(), member);
            Vec3d velocity = velocities.get(member.getUuid());
            if (velocity != null) {
                member.setVelocity(velocity);
                if (!(member instanceof ServerPlayerEntity)) {
                    member.velocityDirty = true;
                }
            }
        }

        if (debugTransition) {
            logGraph("REBUILT", teleportedRoot);
            DEBUG_PROBES.put(teleportedRoot.getUuid(),
                    new DebugProbe(
                            teleportedRoot,
                            teleportedRoot.streamSelfAndPassengers()
                                    .map(Entity::getUuid).collect(java.util.stream.Collectors.toSet()),
                            8));
        }

        // Ordinary mobs are recreated by cross-world teleportation. Their goal
        // objects and navigation are rebuilt, and vanilla does not serialize the
        // live attack target. Restore that relation after the complete passenger
        // graph exists so a pursuit does not stop exactly at the seam.
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

        // VehicleMove validation keeps both an object reference and local-cell
        // coordinates from the previous tick. Rebase them immediately; waiting
        // for the next network-handler tick rejects or corrects the first input
        // packet after the seam and produces a visible one-tick pause.
        for (Entity member : teleportedRoot.streamSelfAndPassengers().toList()) {
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
        }

        // Entity tracking may start in the destination before its passenger
        // entities have spawned on the client. Send the final relation only
        // after the whole graph and the vehicle validation baselines are ready.
        // The client-side handoff queue handles the remaining packet-order race.
        if (teleportedRoot.hasPassengers()) {
            EntityPassengersSetS2CPacket passengers =
                    new EntityPassengersSetS2CPacket(teleportedRoot);
            CellPacketRouting.withSource(targetWorld, () ->
                    targetWorld.getChunkManager()
                            .sendToNearbyPlayers(teleportedRoot, passengers));
            CellViewTracker.sendToShadowPlayers(
                    targetWorld,
                    null,
                    teleportedRoot.getX(),
                    teleportedRoot.getY(),
                    teleportedRoot.getZ(),
                    256.0,
                    passengers);
        }
        for (ServerPlayerEntity player : handoffPlayers) {
            CellPacketRouting.sendFrom(
                    player,
                    targetWorld,
                    new CustomPayloadS2CPacket(
                            new EntityHandoffPayload(teleportedRoot.getId(), false)));
        }
        return true;
    }

    public static boolean isDebugTransition(Entity entity) {
        if (entity == null) {
            return false;
        }
        UUID entityUuid = entity.getUuid();
        UUID rootUuid = entity.getRootVehicle().getUuid();
        return DEBUG_PROBES.containsKey(rootUuid)
                || DEBUG_PROBES.values().stream()
                .anyMatch(probe -> probe.memberUuids().contains(entityUuid));
    }

    private static void logGraph(String phase, Entity root) {
        for (Entity member : root.streamSelfAndPassengers().toList()) {
            Entity vehicle = member.getVehicle();
            Vec3d velocity = member.getVelocity();
            Largerworld.LOGGER.info(
                    "[cell-transition] {} type={} uuid={} id={} object={} world={} "
                            + "pos=({},{},{}) velocity=({},{},{}) vehicleId={} passengers={}",
                    phase,
                    EntityType.getId(member.getType()),
                    member.getUuid(),
                    member.getId(),
                    System.identityHashCode(member),
                    member.getEntityWorld().getRegistryKey().getValue(),
                    member.getX(), member.getY(), member.getZ(),
                    velocity.x, velocity.y, velocity.z,
                    vehicle == null ? -1 : vehicle.getId(),
                    member.getPassengerList().stream().map(Entity::getId).toList());
        }
    }

    private static void tickDebugProbes() {
        Iterator<Map.Entry<UUID, DebugProbe>> iterator = DEBUG_PROBES.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, DebugProbe> entry = iterator.next();
            DebugProbe probe = entry.getValue();
            Entity root = probe.root();
            Vec3d velocity = root.getVelocity();
            Largerworld.LOGGER.info(
                    "[cell-transition] TICK remaining={} type={} uuid={} id={} object={} "
                            + "removed={} world={} pos=({},{},{}) velocity=({},{},{}) passengers={}",
                    probe.remainingTicks(),
                    EntityType.getId(root.getType()),
                    root.getUuid(), root.getId(), System.identityHashCode(root),
                    root.isRemoved(), root.getEntityWorld().getRegistryKey().getValue(),
                    root.getX(), root.getY(), root.getZ(),
                    velocity.x, velocity.y, velocity.z,
                    root.getPassengerList().stream().map(Entity::getId).toList());
            if (probe.remainingTicks() <= 1 || root.isRemoved()) {
                iterator.remove();
            } else {
                entry.setValue(new DebugProbe(
                        root, probe.memberUuids(), probe.remainingTicks() - 1));
            }
        }
    }

    private record DebugProbe(Entity root, Set<UUID> memberUuids, int remainingTicks) {
    }
}
