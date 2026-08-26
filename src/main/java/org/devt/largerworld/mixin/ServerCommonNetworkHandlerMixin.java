package org.devt.largerworld.mixin;

import net.minecraft.network.packet.Packet;
import net.minecraft.server.network.ServerCommonNetworkHandler;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import io.netty.channel.ChannelFutureListener;
import org.devt.largerworld.server.CellPacketRouting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerCommonNetworkHandler.class)
public abstract class ServerCommonNetworkHandlerMixin {
    @Inject(method = "send", at = @At("HEAD"), cancellable = true)
    private void largerworld$tagCellPacket(
            Packet<?> packet, ChannelFutureListener listener, CallbackInfo ci) {
        if ((Object) this instanceof ServerPlayNetworkHandler playHandler) {
            Packet<?> wrapped = CellPacketRouting.wrap(playHandler, packet);
            if (wrapped == null) {
                ci.cancel();
            } else if (wrapped != packet) {
                ci.cancel();
                ((ServerCommonNetworkHandler) (Object) this).send(wrapped, listener);
            }
        }
    }
}
