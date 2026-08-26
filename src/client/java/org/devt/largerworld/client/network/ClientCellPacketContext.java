package org.devt.largerworld.client.network;

import net.minecraft.entity.EntityPosition;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.Vec3d;
import org.devt.largerworld.coordinate.CellPos;
import org.devt.largerworld.coordinate.VirtualChunkPos;
import org.devt.largerworld.coordinate.VirtualPosition;
import org.devt.largerworld.network.CellPacketPayload;

import java.util.Set;

/** Client-thread context used while applying a cell-tagged vanilla packet. */
public final class ClientCellPacketContext {
    private static final ThreadLocal<Mapping> ACTIVE = new ThreadLocal<>();
    private static volatile CellPos connectionOrigin;

    private ClientCellPacketContext() {
    }

    public static void apply(CellPacketPayload payload, ClientPlayPacketListener listener) {
        Mapping previous = ACTIVE.get();
        connectionOrigin = payload.originCell();
        ACTIVE.set(new Mapping(payload.sourceCell(), payload.originCell()));
        try {
            @SuppressWarnings("unchecked")
            Packet<ClientPlayPacketListener> packet = (Packet<ClientPlayPacketListener>) payload.packet();
            packet.apply(listener);
        } finally {
            if (previous == null) {
                ACTIVE.remove();
            } else {
                ACTIVE.set(previous);
            }
        }
    }

    public static CellPos connectionOrigin(CellPos fallback) {
        CellPos origin = connectionOrigin;
        return origin == null ? fallback : origin;
    }

    public static int chunkX(int localX) {
        Mapping mapping = ACTIVE.get();
        return mapping == null ? localX : new VirtualChunkPos(mapping.source(), localX, 0).clientX(mapping.origin());
    }

    public static int chunkZ(int localZ) {
        Mapping mapping = ACTIVE.get();
        return mapping == null ? localZ : new VirtualChunkPos(mapping.source(), 0, localZ).clientZ(mapping.origin());
    }

    public static ChunkPos chunkPos(ChunkPos local) {
        Mapping mapping = ACTIVE.get();
        if (mapping == null) {
            return local;
        }
        VirtualChunkPos virtual = new VirtualChunkPos(mapping.source(), local.x, local.z);
        return new ChunkPos(virtual.clientX(mapping.origin()), virtual.clientZ(mapping.origin()));
    }

    public static ChunkSectionPos sectionPos(ChunkSectionPos local) {
        Mapping mapping = ACTIVE.get();
        if (mapping == null) {
            return local;
        }
        return ChunkSectionPos.from(chunkX(local.getSectionX()), local.getSectionY(), chunkZ(local.getSectionZ()));
    }

    public static BlockPos blockPos(BlockPos local) {
        Mapping mapping = ACTIVE.get();
        if (mapping == null) {
            return local;
        }
        return new BlockPos(
                blockX(local.getX(), mapping),
                local.getY(),
                blockZ(local.getZ(), mapping));
    }

    public static BlockPos blockPos(CellPos source, BlockPos local) {
        Mapping active = ACTIVE.get();
        CellPos origin = active == null ? connectionOrigin : active.origin();
        if (origin == null) {
            return local;
        }
        Mapping mapping = new Mapping(source, origin);
        return new BlockPos(blockX(local.getX(), mapping), local.getY(), blockZ(local.getZ(), mapping));
    }

    public static Vec3d position(Vec3d local) {
        Mapping mapping = ACTIVE.get();
        if (mapping == null) {
            return local;
        }
        return new Vec3d(
                positionX(local.x, mapping),
                local.y,
                positionZ(local.z, mapping));
    }

    public static EntityPosition entityPosition(EntityPosition local) {
        Vec3d translated = position(local.position());
        return translated == local.position() ? local
                : new EntityPosition(translated, local.deltaMovement(), local.yaw(), local.pitch());
    }

    public static EntityPosition entityPosition(EntityPosition local, Set<PositionFlag> relatives) {
        Mapping mapping = ACTIVE.get();
        if (mapping == null) {
            return local;
        }
        Vec3d pos = local.position();
        double x = relatives.contains(PositionFlag.X) ? pos.x : positionX(pos.x, mapping);
        double z = relatives.contains(PositionFlag.Z) ? pos.z : positionZ(pos.z, mapping);
        return new EntityPosition(new Vec3d(x, pos.y, z), local.deltaMovement(), local.yaw(), local.pitch());
    }

    public static double x(double localX) {
        Mapping mapping = ACTIVE.get();
        return mapping == null ? localX : positionX(localX, mapping);
    }

    public static double z(double localZ) {
        Mapping mapping = ACTIVE.get();
        return mapping == null ? localZ : positionZ(localZ, mapping);
    }

    private static int blockX(int localX, Mapping mapping) {
        return Math.toIntExact(Math.addExact((long) localX,
                Math.multiplyExact(mapping.source().x() - mapping.origin().x(), VirtualPosition.CELL_SIZE)));
    }

    private static int blockZ(int localZ, Mapping mapping) {
        return Math.toIntExact(Math.addExact((long) localZ,
                Math.multiplyExact(mapping.source().z() - mapping.origin().z(), VirtualPosition.CELL_SIZE)));
    }

    private static double positionX(double localX, Mapping mapping) {
        return localX + (mapping.source().x() - mapping.origin().x()) * (double) VirtualPosition.CELL_SIZE;
    }

    private static double positionZ(double localZ, Mapping mapping) {
        return localZ + (mapping.source().z() - mapping.origin().z()) * (double) VirtualPosition.CELL_SIZE;
    }

    private record Mapping(CellPos source, CellPos origin) {
    }
}
