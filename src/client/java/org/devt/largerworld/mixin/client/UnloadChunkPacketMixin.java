package org.devt.largerworld.mixin.client;

import net.minecraft.network.packet.s2c.play.UnloadChunkS2CPacket;
import net.minecraft.util.math.ChunkPos;
import org.devt.largerworld.client.network.ClientCellPacketContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(UnloadChunkS2CPacket.class)
public abstract class UnloadChunkPacketMixin {
    @Inject(method = "pos", at = @At("RETURN"), cancellable = true)
    private void largerworld$chunkPos(CallbackInfoReturnable<ChunkPos> cir) {
        cir.setReturnValue(ClientCellPacketContext.chunkPos(cir.getReturnValue()));
    }
}
