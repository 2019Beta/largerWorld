package org.devt.largerworld.mixin;

import net.minecraft.network.packet.Packet;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;
import org.devt.largerworld.server.CellViewTracker;
import org.devt.largerworld.server.CellPacketRouting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

@Mixin(ChunkHolder.class)
public abstract class ChunkHolderMixin {
    @Unique
    private ServerWorld largerworld$flushingWorld;

    @Shadow
    private void sendPacketToPlayers(List<ServerPlayerEntity> players, Packet<?> packet) {
        throw new AssertionError();
    }

    @Inject(method = "flushUpdates", at = @At("HEAD"))
    private void largerworld$beginShadowUpdates(WorldChunk chunk, CallbackInfo ci) {
        if (chunk.getWorld() instanceof ServerWorld world) {
            largerworld$flushingWorld = world;
        }
    }

    @Inject(method = "flushUpdates", at = @At("RETURN"))
    private void largerworld$endShadowUpdates(WorldChunk chunk, CallbackInfo ci) {
        largerworld$flushingWorld = null;
    }

    @Redirect(
            method = "flushUpdates",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/world/ChunkHolder$PlayersWatchingChunkProvider;getPlayersWatchingChunk(Lnet/minecraft/util/math/ChunkPos;Z)Ljava/util/List;"))
    private List<ServerPlayerEntity> largerworld$includeShadowWatchers(
            ChunkHolder.PlayersWatchingChunkProvider provider, ChunkPos pos, boolean edgeOnly) {
        List<ServerPlayerEntity> vanilla = provider.getPlayersWatchingChunk(pos, edgeOnly);
        ServerWorld world = largerworld$flushingWorld;
        if (world == null) {
            return vanilla;
        }
        List<ServerPlayerEntity> combined = new ArrayList<>(vanilla);
        for (ServerPlayerEntity player : CellViewTracker.watchers(world, pos)) {
            if (player.getEntityWorld() != world && !combined.contains(player)) {
                combined.add(player);
            }
        }
        return combined;
    }

    @Redirect(
            method = "flushUpdates",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/world/ChunkHolder;sendPacketToPlayers(Ljava/util/List;Lnet/minecraft/network/packet/Packet;)V"))
    private void largerworld$tagIncrementalUpdateSource(
            ChunkHolder holder, List<ServerPlayerEntity> players, Packet<?> packet) {
        ServerWorld world = largerworld$flushingWorld;
        if (world == null) {
            sendPacketToPlayers(players, packet);
            return;
        }
        CellPacketRouting.withSource(world, () -> sendPacketToPlayers(players, packet));
    }
}
