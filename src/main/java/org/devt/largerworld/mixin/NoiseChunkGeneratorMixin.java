package org.devt.largerworld.mixin;

import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.gen.chunk.ChunkNoiseSampler;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;
import net.minecraft.world.gen.noise.NoiseConfig;
import org.devt.largerworld.coordinate.CellPos;
import org.devt.largerworld.world.WorldgenCoordinates;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Mixin(NoiseChunkGenerator.class)
public abstract class NoiseChunkGeneratorMixin {
    @ModifyArgs(
            method = "carve",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/util/math/random/ChunkRandom;setCarverSeed(JII)V"))
    private void largerworld$seedCarversFromGlobalChunk(Args args) {
        CellPos cell = WorldgenCoordinates.cell((NoiseChunkGenerator) (Object) this);
        ChunkPos globalPos = WorldgenCoordinates.toGlobalChunk(
                cell, new ChunkPos((Integer) args.get(1), (Integer) args.get(2)));
        args.set(1, globalPos.x);
        args.set(2, globalPos.z);
    }

    @ModifyArgs(
            method = {"getHeight", "getColumnSample"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/gen/chunk/NoiseChunkGenerator;sampleHeightmap(Lnet/minecraft/world/HeightLimitView;Lnet/minecraft/world/gen/noise/NoiseConfig;IILorg/apache/commons/lang3/mutable/MutableObject;Ljava/util/function/Predicate;)Ljava/util/OptionalInt;"))
    private void largerworld$offsetHeightSample(Args args) {
        NoiseConfig noiseConfig = args.get(1);
        CellPos cell = WorldgenCoordinates.cell(noiseConfig);
        args.set(2, WorldgenCoordinates.toGlobalBlockX(cell, (Integer) args.get(2)));
        args.set(3, WorldgenCoordinates.toGlobalBlockZ(cell, (Integer) args.get(3)));
    }

    @Redirect(
            method = "getBlockState",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/gen/chunk/ChunkNoiseSampler;estimateSurfaceHeight(II)I"))
    private int largerworld$estimateDebugAquiferSurfaceAtGlobalPosition(
            ChunkNoiseSampler sampler, int localX, int localZ) {
        CellPos cell = WorldgenCoordinates.cell(sampler);
        return sampler.estimateSurfaceHeight(
                WorldgenCoordinates.toGlobalBlockX(cell, localX),
                WorldgenCoordinates.toGlobalBlockZ(cell, localZ));
    }

    @Redirect(
            method = "populateNoise(Lnet/minecraft/world/gen/chunk/Blender;Lnet/minecraft/world/gen/StructureAccessor;Lnet/minecraft/world/gen/noise/NoiseConfig;Lnet/minecraft/world/chunk/Chunk;II)Lnet/minecraft/world/chunk/Chunk;",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/gen/chunk/ChunkNoiseSampler;interpolateX(ID)V"))
    private void largerworld$interpolateGlobalX(ChunkNoiseSampler sampler, int localX, double delta) {
        CellPos cell = WorldgenCoordinates.cell(sampler);
        sampler.interpolateX(WorldgenCoordinates.toGlobalBlockX(cell, localX), delta);
    }

    @Redirect(
            method = "populateNoise(Lnet/minecraft/world/gen/chunk/Blender;Lnet/minecraft/world/gen/StructureAccessor;Lnet/minecraft/world/gen/noise/NoiseConfig;Lnet/minecraft/world/chunk/Chunk;II)Lnet/minecraft/world/chunk/Chunk;",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/gen/chunk/ChunkNoiseSampler;interpolateZ(ID)V"))
    private void largerworld$interpolateGlobalZ(ChunkNoiseSampler sampler, int localZ, double delta) {
        CellPos cell = WorldgenCoordinates.cell(sampler);
        sampler.interpolateZ(WorldgenCoordinates.toGlobalBlockZ(cell, localZ), delta);
    }
}
