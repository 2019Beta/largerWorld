package org.devt.largerworld.world;

import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.BlockView;
import org.devt.largerworld.coordinate.CellPos;
import org.devt.largerworld.coordinate.VirtualPosition;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Coordinate projections used by server-side behavior at a cell seam. */
public final class CellBoundaryAccess {
    private CellBoundaryAccess() {
    }

    public static Optional<ResolvedBlock> resolveLoadedBlock(ServerWorld source, BlockPos sourcePos) {
        long deltaX = Math.floorDiv((long) sourcePos.getX() + VirtualPosition.HALF_CELL,
                VirtualPosition.CELL_SIZE);
        long deltaZ = Math.floorDiv((long) sourcePos.getZ() + VirtualPosition.HALF_CELL,
                VirtualPosition.CELL_SIZE);
        if (deltaX == 0 && deltaZ == 0) {
            return Optional.empty();
        }

        CellPos targetCell;
        try {
            targetCell = CellWorldKey.cell(source.getRegistryKey()).add(deltaX, deltaZ);
        } catch (ArithmeticException exception) {
            return Optional.empty();
        }
        ServerWorld target = CellWorldManager.getIfLoaded(
                source.getServer(), CellWorldKey.baseWorld(source.getRegistryKey()), targetCell);
        if (target == null) {
            return Optional.empty();
        }

        long localX = sourcePos.getX() - deltaX * VirtualPosition.CELL_SIZE;
        long localZ = sourcePos.getZ() - deltaZ * VirtualPosition.CELL_SIZE;
        return Optional.of(new ResolvedBlock(
                target, new BlockPos((int) localX, sourcePos.getY(), (int) localZ)));
    }

    public static Optional<BlockView> resolveLoadedChunkView(
            ServerWorld source, int sourceChunkX, int sourceChunkZ) {
        long deltaX = Math.floorDiv((long) sourceChunkX + VirtualPosition.HALF_CELL / 16,
                VirtualPosition.CELL_SIZE / 16);
        long deltaZ = Math.floorDiv((long) sourceChunkZ + VirtualPosition.HALF_CELL / 16,
                VirtualPosition.CELL_SIZE / 16);
        if (deltaX == 0 && deltaZ == 0) {
            return Optional.empty();
        }
        CellPos targetCell;
        try {
            targetCell = CellWorldKey.cell(source.getRegistryKey()).add(deltaX, deltaZ);
        } catch (ArithmeticException exception) {
            return Optional.empty();
        }
        ServerWorld target = CellWorldManager.getIfLoaded(
                source.getServer(), CellWorldKey.baseWorld(source.getRegistryKey()), targetCell);
        if (target == null) {
            return Optional.empty();
        }
        long localX = sourceChunkX - deltaX * (VirtualPosition.CELL_SIZE / 16);
        long localZ = sourceChunkZ - deltaZ * (VirtualPosition.CELL_SIZE / 16);
        return Optional.ofNullable(target.getChunkAsView((int) localX, (int) localZ));
    }

    /** Returns {@code target}'s position expressed in {@code observerWorld}'s local coordinates. */
    public static Optional<Vec3d> project(Entity target, World observerWorld) {
        if (!(observerWorld instanceof ServerWorld observer)
                || !(target.getEntityWorld() instanceof ServerWorld targetWorld)
                || !CellWorldKey.baseWorld(observer.getRegistryKey())
                .equals(CellWorldKey.baseWorld(targetWorld.getRegistryKey()))) {
            return Optional.empty();
        }
        CellPos observerCell = CellWorldKey.cell(observer.getRegistryKey());
        CellPos targetCell = CellWorldKey.cell(targetWorld.getRegistryKey());
        long deltaX;
        long deltaZ;
        try {
            deltaX = targetCell.deltaXExact(observerCell);
            deltaZ = targetCell.deltaZExact(observerCell);
        } catch (ArithmeticException exception) {
            return Optional.empty();
        }
        if (deltaX == 0 && deltaZ == 0) {
            return Optional.of(target.getEntityPos());
        }
        // Behavior bridging is intentionally local. Projecting a very distant cell
        // into vanilla doubles/BlockPos would recreate the coordinate overflow this
        // partitioning is designed to avoid.
        if (deltaX < -1 || deltaX > 1 || deltaZ < -1 || deltaZ > 1) {
            return Optional.empty();
        }
        return Optional.of(new Vec3d(
                target.getX() + deltaX * (double) VirtualPosition.CELL_SIZE,
                target.getY(),
                target.getZ() + deltaZ * (double) VirtualPosition.CELL_SIZE));
    }

    public static OptionalDoubleDistance squaredDistance(Entity observer, Entity target) {
        Optional<Vec3d> projected = project(target, observer.getEntityWorld());
        if (projected.isEmpty() || observer.getEntityWorld() == target.getEntityWorld()) {
            return OptionalDoubleDistance.empty();
        }
        Vec3d position = projected.get();
        double dx = observer.getX() - position.x;
        double dy = observer.getY() - position.y;
        double dz = observer.getZ() - position.z;
        return OptionalDoubleDistance.of(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Resolves an entity reference in this cell or an immediately adjacent loaded cell.
     * Vanilla lazy references only query their current ServerWorld, which makes owners,
     * leash holders and other entity relationships disappear at a cell seam.
     */
    public static Optional<Entity> findLoadedEntity(ServerWorld source, UUID uuid) {
        for (ServerWorld candidateWorld : source.getServer().getWorlds()) {
            if (!CellWorldKey.baseWorld(source.getRegistryKey())
                    .equals(CellWorldKey.baseWorld(candidateWorld.getRegistryKey()))) {
                continue;
            }
            CellPos sourceCell = CellWorldKey.cell(source.getRegistryKey());
            CellPos candidateCell = CellWorldKey.cell(candidateWorld.getRegistryKey());
            long dx;
            long dz;
            try {
                dx = candidateCell.deltaXExact(sourceCell);
                dz = candidateCell.deltaZExact(sourceCell);
            } catch (ArithmeticException exception) {
                continue;
            }
            if (dx < -1 || dx > 1 || dz < -1 || dz > 1) {
                continue;
            }
            for (Entity entity : candidateWorld.iterateEntities()) {
                if (entity.getUuid().equals(uuid) && !entity.isRemoved()) {
                    return Optional.of(entity);
                }
            }
        }
        return Optional.empty();
    }

    /** Loaded neighboring worlds whose cells overlap a source-local search box. */
    public static List<ProjectedWorld> loadedWorldsOverlapping(ServerWorld source, Box sourceBox) {
        long minDeltaX = Math.floorDiv((long) Math.floor(sourceBox.minX) + VirtualPosition.HALF_CELL,
                VirtualPosition.CELL_SIZE);
        long maxDeltaX = Math.floorDiv((long) Math.floor(sourceBox.maxX) + VirtualPosition.HALF_CELL,
                VirtualPosition.CELL_SIZE);
        long minDeltaZ = Math.floorDiv((long) Math.floor(sourceBox.minZ) + VirtualPosition.HALF_CELL,
                VirtualPosition.CELL_SIZE);
        long maxDeltaZ = Math.floorDiv((long) Math.floor(sourceBox.maxZ) + VirtualPosition.HALF_CELL,
                VirtualPosition.CELL_SIZE);
        CellPos sourceCell = CellWorldKey.cell(source.getRegistryKey());
        MinecraftServer server = source.getServer();
        List<ProjectedWorld> result = new ArrayList<>();
        for (long dz = Math.max(-1, minDeltaZ); dz <= Math.min(1, maxDeltaZ); dz++) {
            for (long dx = Math.max(-1, minDeltaX); dx <= Math.min(1, maxDeltaX); dx++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                CellPos cell;
                try {
                    cell = sourceCell.add(dx, dz);
                } catch (ArithmeticException exception) {
                    continue;
                }
                ServerWorld world = CellWorldManager.getIfLoaded(
                        server, CellWorldKey.baseWorld(source.getRegistryKey()), cell);
                if (world != null) {
                    result.add(new ProjectedWorld(world, sourceBox.offset(
                            -dx * (double) VirtualPosition.CELL_SIZE,
                            0,
                            -dz * (double) VirtualPosition.CELL_SIZE)));
                }
            }
        }
        return result;
    }

    public record ResolvedBlock(ServerWorld world, BlockPos pos) {
    }

    public record ProjectedWorld(ServerWorld world, Box localBox) {
    }

    /** Small explicit optional specialized for a computed distance. */
    public record OptionalDoubleDistance(boolean present, double value) {
        public static OptionalDoubleDistance empty() {
            return new OptionalDoubleDistance(false, 0.0);
        }

        public static OptionalDoubleDistance of(double value) {
            return new OptionalDoubleDistance(true, value);
        }
    }
}
