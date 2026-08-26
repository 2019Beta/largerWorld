package org.devt.largerworld.mixin;

import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.random.ChunkRandom;
import net.minecraft.world.gen.noise.NoiseConfig;
import net.minecraft.world.gen.structure.Structure;
import org.devt.largerworld.coordinate.CellPos;
import org.devt.largerworld.world.WorldgenCoordinates;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Structure.Context.class)
public abstract class StructureContextMixin {
    @Shadow @Final private NoiseConfig noiseConfig;
    @Shadow @Final private ChunkRandom random;
    @Shadow @Final private long seed;
    @Shadow @Final private ChunkPos chunkPos;

    @Inject(
            method = "<init>(Lnet/minecraft/registry/DynamicRegistryManager;Lnet/minecraft/world/gen/chunk/ChunkGenerator;Lnet/minecraft/world/biome/source/BiomeSource;Lnet/minecraft/world/gen/noise/NoiseConfig;Lnet/minecraft/structure/StructureTemplateManager;JLnet/minecraft/util/math/ChunkPos;Lnet/minecraft/world/HeightLimitView;Ljava/util/function/Predicate;)V",
            at = @At("RETURN"))
    private void largerworld$seedStructureLayoutFromGlobalChunk(CallbackInfo ci) {
        CellPos cell = WorldgenCoordinates.cell(noiseConfig);
        ChunkPos globalPos = WorldgenCoordinates.toGlobalChunk(cell, chunkPos);
        random.setCarverSeed(seed, globalPos.x, globalPos.z);
    }
}
