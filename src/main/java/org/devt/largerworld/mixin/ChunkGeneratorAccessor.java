package org.devt.largerworld.mixin;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ChunkGenerator.class)
public interface ChunkGeneratorAccessor {
    @Invoker("getCodec")
    MapCodec<? extends ChunkGenerator> largerworld$invokeGetCodec();
}
