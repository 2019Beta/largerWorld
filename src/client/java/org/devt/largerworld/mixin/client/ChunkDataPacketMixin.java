package org.devt.largerworld.mixin.client;

import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import org.devt.largerworld.client.network.ClientCellPacketContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkDataS2CPacket.class)
public abstract class ChunkDataPacketMixin {
    @Inject(method = "getChunkX", at = @At("RETURN"), cancellable = true)
    private void largerworld$chunkX(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(ClientCellPacketContext.chunkX(cir.getReturnValue()));
    }

    @Inject(method = "getChunkZ", at = @At("RETURN"), cancellable = true)
    private void largerworld$chunkZ(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(ClientCellPacketContext.chunkZ(cir.getReturnValue()));
    }
}
