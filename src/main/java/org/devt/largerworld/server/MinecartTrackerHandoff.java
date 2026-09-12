package org.devt.largerworld.server;

import net.minecraft.entity.Entity;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.entity.vehicle.DefaultMinecartController;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import org.devt.largerworld.mixin.ServerChunkLoadingManagerAccessor;

/** Keeps the packet timeline of a default minecart across tracker replacement. */
public final class MinecartTrackerHandoff {
    private static final ThreadLocal<Pending> PENDING = new ThreadLocal<>();

    private MinecartTrackerHandoff() {
    }

    public interface Entry {
        State largerworld$captureMinecartTracking();

        void largerworld$restoreMinecartTracking(State state, Vec3d offset);
    }

    public record State(
            Vec3d position, Vec3d velocity, int trackingTick, int updatesWithoutVehicle,
            byte yaw, byte pitch, byte headYaw, boolean hadVehicle, boolean onGround) {
    }

    public static State capture(Entity entity) {
        if (!SeamlessCellTeleport.isContinuousMovement()
                || !(entity instanceof AbstractMinecartEntity minecart)
                || !(minecart.getController() instanceof DefaultMinecartController)
                || !(entity.getEntityWorld() instanceof ServerWorld world)) {
            return null;
        }
        Object tracker = ((ServerChunkLoadingManagerAccessor) world.getChunkManager().chunkLoadingManager)
                .largerworld$getEntityTrackers().get(entity.getId());
        return tracker instanceof CellEntityTracker cellTracker
                && cellTracker.largerworld$getEntity() == entity
                ? ((Entry) cellTracker.largerworld$getEntry()).largerworld$captureMinecartTracking()
                : null;
    }

    public static void register(
            Entity entity, ServerWorld world, State state, Vec3d offset, Runnable action) {
        if (state == null) {
            action.run();
            return;
        }
        Pending previous = PENDING.get();
        PENDING.set(new Pending(entity, world, state, offset));
        try {
            action.run();
        } finally {
            if (previous == null) {
                PENDING.remove();
            } else {
                PENDING.set(previous);
            }
        }
    }

    /** Called before the new tracker can emit any spawn or movement packet. */
    public static void restore(Entry entry, Entity entity, ServerWorld world) {
        Pending pending = PENDING.get();
        if (pending != null && pending.entity() == entity && pending.world() == world) {
            entry.largerworld$restoreMinecartTracking(pending.state(), pending.offset());
        }
    }

    private record Pending(Entity entity, ServerWorld world, State state, Vec3d offset) {
    }
}
