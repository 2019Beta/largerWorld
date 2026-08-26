package org.devt.largerworld.mixin.client;

import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.util.math.BlockPos;
import org.devt.largerworld.client.network.ClientCellPacketContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(ChunkDeltaUpdateS2CPacket.class)
public abstract class ChunkDeltaPacketMixin {
    @ModifyArg(
            method = "visitUpdates",
            at = @At(value = "INVOKE", target = "Ljava/util/function/BiConsumer;accept(Ljava/lang/Object;Ljava/lang/Object;)V"),
            index = 0)
    private Object largerworld$blockPos(Object pos) {
        return ClientCellPacketContext.blockPos((BlockPos) pos);
    }
}
