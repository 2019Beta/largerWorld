package org.devt.largerworld.mixin.client;

import net.minecraft.network.packet.s2c.play.BlockBreakingProgressS2CPacket;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.BlockEventS2CPacket;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.WorldEventS2CPacket;
import net.minecraft.network.packet.s2c.play.SignEditorOpenS2CPacket;
import net.minecraft.util.math.BlockPos;
import org.devt.largerworld.client.network.ClientCellPacketContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin({
        BlockBreakingProgressS2CPacket.class,
        BlockEntityUpdateS2CPacket.class,
        BlockEventS2CPacket.class,
        BlockUpdateS2CPacket.class,
        WorldEventS2CPacket.class,
        SignEditorOpenS2CPacket.class
})
public abstract class BlockPositionPacketMixin {
    @Inject(method = "getPos", at = @At("RETURN"), cancellable = true)
    private void largerworld$blockPos(CallbackInfoReturnable<BlockPos> cir) {
        cir.setReturnValue(ClientCellPacketContext.blockPos(cir.getReturnValue()));
    }
}
