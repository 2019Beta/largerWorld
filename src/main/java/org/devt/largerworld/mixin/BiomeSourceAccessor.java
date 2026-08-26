package org.devt.largerworld.mixin;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.biome.source.BiomeSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(BiomeSource.class)
public interface BiomeSourceAccessor {
    @Invoker("getCodec")
    MapCodec<? extends BiomeSource> largerworld$invokeGetCodec();
}
