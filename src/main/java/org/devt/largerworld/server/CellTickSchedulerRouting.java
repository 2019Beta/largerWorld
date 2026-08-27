package org.devt.largerworld.server;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.tick.OrderedTick;
import net.minecraft.world.tick.WorldTickScheduler;
import org.devt.largerworld.world.CellBoundaryAccess;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** Routes scheduled block/fluid ticks whose position belongs to a neighboring cell. */
public final class CellTickSchedulerRouting {
    public enum Kind {
        BLOCK,
        FLUID
    }

    private static final Map<WorldTickScheduler<?>, Owner> OWNERS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private CellTickSchedulerRouting() {
    }

    public static void register(WorldTickScheduler<?> scheduler, ServerWorld world, Kind kind) {
        OWNERS.put(scheduler, new Owner(world, kind));
    }

    public static boolean routeSchedule(WorldTickScheduler<?> scheduler, OrderedTick<?> tick) {
        Owner owner = OWNERS.get(scheduler);
        if (owner == null) {
            return false;
        }
        return CellBoundaryAccess.resolveLoadedBlock(owner.world(), tick.pos())
                .map(resolved -> {
                    WorldTickScheduler<?> target = scheduler(resolved.world(), owner.kind());
                    scheduleUnchecked(target, new OrderedTick<>(
                            tick.type(),
                            resolved.pos(),
                            tick.triggerTick(),
                            tick.priority(),
                            tick.subTickOrder()));
                    return true;
                })
                .orElse(false);
    }

    public static Boolean routeIsQueued(
            WorldTickScheduler<?> scheduler, BlockPos pos, Object type, boolean ticking) {
        Owner owner = OWNERS.get(scheduler);
        if (owner == null) {
            return null;
        }
        return CellBoundaryAccess.resolveLoadedBlock(owner.world(), pos)
                .map(resolved -> {
                    WorldTickScheduler<?> target = scheduler(resolved.world(), owner.kind());
                    return ticking
                            ? isTickingUnchecked(target, resolved.pos(), type)
                            : isQueuedUnchecked(target, resolved.pos(), type);
                })
                .orElse(null);
    }

    private static WorldTickScheduler<?> scheduler(ServerWorld world, Kind kind) {
        return kind == Kind.BLOCK
                ? world.getBlockTickScheduler()
                : world.getFluidTickScheduler();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void scheduleUnchecked(WorldTickScheduler scheduler, OrderedTick<?> tick) {
        scheduler.scheduleTick(tick);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean isQueuedUnchecked(
            WorldTickScheduler scheduler, BlockPos pos, Object type) {
        return scheduler.isQueued(pos, type);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean isTickingUnchecked(
            WorldTickScheduler scheduler, BlockPos pos, Object type) {
        return scheduler.isTicking(pos, type);
    }

    private record Owner(ServerWorld world, Kind kind) {
    }
}
