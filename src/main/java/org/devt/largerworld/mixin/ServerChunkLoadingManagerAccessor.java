package org.devt.largerworld.mixin;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.server.world.ServerChunkLoadingManager;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ServerChunkLoadingManager.class)
public interface ServerChunkLoadingManagerAccessor {
    @Accessor("entityTrackers")
    Int2ObjectMap<?> largerworld$getEntityTrackers();

    @Invoker("save")
    boolean largerworld$saveChunk(Chunk chunk);
}
