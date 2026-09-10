package org.devt.largerworld.mixin.client;

import net.minecraft.client.network.PendingUpdateManager;
import net.minecraft.client.world.ClientChunkManager;
import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;
import java.util.Deque;

@Mixin(ClientWorld.class)
public interface ClientWorldOriginAccessor {
    @Mutable @Accessor("chunkManager")
    void largerworld$setChunkManager(ClientChunkManager manager);
    @Accessor("pendingUpdateManager")
    PendingUpdateManager largerworld$pendingUpdates();
    @Accessor("chunkUpdaters")
    Deque<Runnable> largerworld$chunkUpdates();
}
