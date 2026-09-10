package org.devt.largerworld.mixin.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientCommonNetworkHandler;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.listener.ServerPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.common.CustomPayloadC2SPacket;
import org.devt.largerworld.Largerworld;
import org.devt.largerworld.client.network.ClientCellPacketContext;
import org.devt.largerworld.network.CellInputPayload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientCommonNetworkHandler.class)
public abstract class ClientInputOriginMixin {
    @Inject(method = "sendPacket", at = @At("HEAD"), cancellable = true)
    private void largerworld$tagInputOrigin(Packet<?> packet, CallbackInfo ci) {
        if (!((Object) this instanceof ClientPlayNetworkHandler)
                || !CellInputPayload.isCoordinatePacket(packet)) {
            return;
        }
        var player = MinecraftClient.getInstance().player;
        if (player == null) {
            return;
        }
        @SuppressWarnings("unchecked")
        Packet<? super ServerPlayPacketListener> input = (Packet<? super ServerPlayPacketListener>) packet;
        var origin = ClientCellPacketContext.connectionOrigin(player.getAttachedOrCreate(Largerworld.CELL_POS));
        ci.cancel();
        ((ClientCommonNetworkHandler) (Object) this).sendPacket(
                new CustomPayloadC2SPacket(new CellInputPayload(origin, input)));
    }
}
