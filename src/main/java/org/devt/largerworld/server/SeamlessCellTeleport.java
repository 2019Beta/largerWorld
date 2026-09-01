package org.devt.largerworld.server;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityPosition;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.TeleportTarget;
import org.devt.largerworld.Largerworld;
import org.devt.largerworld.mixin.EntityAccessor;
import org.devt.largerworld.mixin.TeleportTargetAccessor;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Performs cell-to-cell moves without replacing server entity instances. */
public final class SeamlessCellTeleport {
    private static final ThreadLocal<HandoffMode> HANDOFF_MODE =
            ThreadLocal.withInitial(() -> HandoffMode.NONE);

    private SeamlessCellTeleport() {
    }

    /** Runs a cross-cell teleport and exposes its packet semantics to mixins. */
    public static <T> T withCellHandoff(boolean continuousMovement, Supplier<T> action) {
        HandoffMode previous = HANDOFF_MODE.get();
        HANDOFF_MODE.set(continuousMovement ? HandoffMode.CONTINUOUS : HandoffMode.TELEPORT);
        try {
            return action.get();
        } finally {
            if (previous == HandoffMode.NONE) {
                HANDOFF_MODE.remove();
            } else {
                HANDOFF_MODE.set(previous);
            }
        }
    }

    public static boolean isCellHandoff() {
        return HANDOFF_MODE.get() != HandoffMode.NONE;
    }

    public static boolean isContinuousMovement() {
        return HANDOFF_MODE.get() == HandoffMode.CONTINUOUS;
    }

    public static ServerPlayerEntity teleport(ServerPlayerEntity player, TeleportTarget target) {
        ServerWorld from = player.getEntityWorld();
        ServerWorld to = target.world();
        if (!((TeleportTargetAccessor) (Object) target).largerworld$isAsPassenger()) {
            player.dismountVehicle();
        }

        // The caller enters the target source context before teleportTo so the
        // eventual destination packets are mapped correctly. Removal happens
        // first, however, and its chunk/entity unload packets still describe
        // the old world. Explicitly restore that source or the client unloads
        // the already-preloaded destination view at the same mapped positions.
        CellPacketRouting.withSource(from, () ->
                from.removePlayer(player, net.minecraft.entity.Entity.RemovalReason.CHANGED_DIMENSION));
        ((EntityAccessor) player).largerworld$unsetRemoved();
        player.setServerWorld(to);

        EntityPosition targetPosition = EntityPosition.fromTeleportTarget(target);
        CellPacketRouting.withSource(to, () -> {
            if (isContinuousMovement()) {
                // The movement packet that crossed the seam already placed the
                // client at this exact stitched-world coordinate. Sending an
                // equivalent PlayerPositionLook packet would reset interpolation.
                // Apply a relative zero velocity delta on the server so both the
                // server player and its locally controlled client retain momentum.
                EntityPosition velocityPreservingPosition = new EntityPosition(
                        targetPosition.position(),
                        Vec3d.ZERO,
                        targetPosition.yaw(),
                        targetPosition.pitch());
                var positionFlags = PositionFlag.combine(target.relatives(), PositionFlag.DELTA);
                player.setPosition(velocityPreservingPosition, positionFlags);
            } else {
                player.networkHandler.requestTeleport(targetPosition, target.relatives());
            }
            player.networkHandler.syncWithPlayerPosition();
            to.onDimensionChanged(player);
            target.postTeleportTransition().onTransition(player);
        });
        return player;
    }

    /**
     * Moves a complete riding graph between cell worlds without replacing any
     * of its server-side entity instances.
     */
    public static Entity teleportGraphInPlace(Entity root, TeleportTarget target) {
        if (!(root.getEntityWorld() instanceof ServerWorld from)) {
            return null;
        }
        ServerWorld to = target.world();
        if (from == to || root.isRemoved()) {
            return null;
        }

        List<Entity> members = root.streamSelfAndPassengers().toList();
        if (!canTransfer(members, from, to)) {
            return null;
        }

        Vec3d rootPosition = root.getEntityPos();
        Map<Entity, TransferState> sourceStates = new IdentityHashMap<>();
        Map<Entity, TransferState> targetStates = new IdentityHashMap<>();
        Map<Entity, Entity> vehicles = new IdentityHashMap<>();
        for (Entity member : members) {
            TransferState sourceState = TransferState.capture(member);
            sourceStates.put(member, sourceState);
            targetStates.put(member, sourceState.translated(
                    target.position().subtract(rootPosition)));
            if (member.hasVehicle()) {
                vehicles.put(member, member.getVehicle());
            }
        }

        CellPacketRouting.withSource(from, () -> removeGraph(members));

        List<Entity> registered = new ArrayList<>(members.size());
        try {
            for (Entity member : members) {
                bind(member, to, targetStates.get(member));
            }
            CellPacketRouting.withSource(to, () -> {
                for (Entity member : members) {
                    register(member, to, targetStates.get(member));
                    registered.add(member);
                }
                restoreRidingGraph(members, vehicles);
                for (Entity member : members) {
                    if (to.getEntity(member.getUuid()) != member
                            || member.getEntityWorld() != to
                            || member.isRemoved()) {
                        throw new IllegalStateException(
                                "Destination did not retain entity instance "
                                        + member.getUuid());
                    }
                }
                for (Entity member : members) {
                    target.postTeleportTransition().onTransition(member);
                }
            });
            return root;
        } catch (RuntimeException exception) {
            Largerworld.LOGGER.error(
                    "In-place cell transfer failed for entity graph {}; restoring source world",
                    root.getUuid(), exception);
            rollback(from, to, members, registered, sourceStates, vehicles);
            return null;
        }
    }

    private static boolean canTransfer(
            List<Entity> members, ServerWorld from, ServerWorld to) {
        for (Entity member : members) {
            if (member.isRemoved() || member.getEntityWorld() != from) {
                return false;
            }
            Entity uuidCollision = to.getEntity(member.getUuid());
            Entity idCollision = to.getEntityById(member.getId());
            if (uuidCollision != null || idCollision != null) {
                Largerworld.LOGGER.warn(
                        "Refusing in-place cell transfer for {}: destination collision uuid={} id={}",
                        member.getUuid(), uuidCollision != null, idCollision != null);
                return false;
            }
        }
        return true;
    }

    private static void bind(Entity entity, ServerWorld world, TransferState state) {
        EntityAccessor accessor = (EntityAccessor) entity;
        accessor.largerworld$unsetRemoved();
        if (entity instanceof MobEntity mob) {
            mob.getNavigation().stop();
        }
        if (entity instanceof ServerPlayerEntity player) {
            player.setServerWorld(world);
        } else {
            accessor.largerworld$setWorld(world);
        }
        if (entity instanceof MobEntity mob) {
            ((CellNavigationWorldBinding) mob.getNavigation())
                    .largerworld$setNavigationWorld(world);
        }
        entity.refreshPositionAndAngles(
                state.position(), state.yaw(), state.pitch());
        accessor.largerworld$setLastPos(state.position());
        entity.updateTrackedPosition(
                state.position().x, state.position().y, state.position().z);
        entity.setVelocity(state.velocity());
    }

    private static void removeGraph(List<Entity> members) {
        // Remove leaves first. When each parent later detaches its passengers,
        // their CHANGED_DIMENSION marker suppresses synthetic dismount events.
        for (int index = members.size() - 1; index >= 0; index--) {
            Entity member = members.get(index);
            if (!member.isRemoved()) {
                member.remove(Entity.RemovalReason.CHANGED_DIMENSION);
            }
        }
    }

    private static void restoreRidingGraph(
            List<Entity> members, Map<Entity, Entity> vehicles) {
        for (Entity passenger : members) {
            Entity vehicle = vehicles.get(passenger);
            if (vehicle != null && !passenger.startRiding(vehicle, true, false)) {
                throw new IllegalStateException(
                        "Could not restore passenger " + passenger.getUuid()
                                + " on vehicle " + vehicle.getUuid());
            }
        }
    }

    private static void register(
            Entity entity, ServerWorld world, TransferState state) {
        if (entity instanceof ServerPlayerEntity player) {
            positionPlayer(player, state);
            world.onDimensionChanged(player);
        } else if (!world.spawnEntity(entity)) {
            throw new IllegalStateException(
                    "Destination rejected entity " + entity.getUuid());
        }
    }

    private static void positionPlayer(ServerPlayerEntity player, TransferState state) {
        Vec3d packetVelocity = isContinuousMovement()
                ? Vec3d.ZERO : state.velocity();
        EntityPosition position = new EntityPosition(
                state.position(), packetVelocity, state.yaw(), state.pitch());
        if (isContinuousMovement()) {
            player.setPosition(position, PositionFlag.combine(
                    java.util.Set.of(), PositionFlag.DELTA));
        } else {
            player.networkHandler.requestTeleport(position, java.util.Set.of());
        }
        player.networkHandler.syncWithPlayerPosition();
    }

    private static void rollback(
            ServerWorld from,
            ServerWorld to,
            List<Entity> members,
            List<Entity> registered,
            Map<Entity, TransferState> sourceStates,
            Map<Entity, Entity> vehicles) {
        if (!registered.isEmpty()) {
            CellPacketRouting.withSource(to, () -> removeGraph(members));
        }
        for (Entity member : members) {
            bind(member, from, sourceStates.get(member));
        }
        CellPacketRouting.withSource(from, () -> {
            for (Entity member : members) {
                register(member, from, sourceStates.get(member));
            }
            restoreRidingGraph(members, vehicles);
        });
    }

    private record TransferState(
            Vec3d position, Vec3d velocity, float yaw, float pitch) {
        private static TransferState capture(Entity entity) {
            return new TransferState(
                    entity.getEntityPos(), entity.getVelocity(),
                    entity.getYaw(), entity.getPitch());
        }

        private TransferState translated(Vec3d offset) {
            return new TransferState(position.add(offset), velocity, yaw, pitch);
        }
    }

    private enum HandoffMode {
        NONE,
        TELEPORT,
        CONTINUOUS
    }
}
