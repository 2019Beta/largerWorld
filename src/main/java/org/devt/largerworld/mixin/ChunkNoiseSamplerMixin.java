package org.devt.largerworld.mixin;

import net.minecraft.world.gen.chunk.ChunkNoiseSampler;
import net.minecraft.world.gen.noise.NoiseConfig;
import org.devt.largerworld.coordinate.CellPos;
import org.devt.largerworld.world.WorldgenCoordinates;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Mixin(ChunkNoiseSampler.class)
public abstract class ChunkNoiseSamplerMixin {
    @ModifyArgs(
            method = "create",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/gen/chunk/ChunkNoiseSampler;<init>(ILnet/minecraft/world/gen/noise/NoiseConfig;IILnet/minecraft/world/gen/chunk/GenerationShapeConfig;Lnet/minecraft/world/gen/densityfunction/DensityFunctionTypes$Beardifying;Lnet/minecraft/world/gen/chunk/ChunkGeneratorSettings;Lnet/minecraft/world/gen/chunk/AquiferSampler$FluidLevelSampler;Lnet/minecraft/world/gen/chunk/Blender;)V"))
    private static void largerworld$sampleAtGlobalHorizontalPosition(Args args) {
        NoiseConfig noiseConfig = args.get(1);
        CellPos cell = WorldgenCoordinates.cell(noiseConfig);
        if (cell.equals(CellPos.ZERO)) {
            return;
        }

        args.set(2, WorldgenCoordinates.toGlobalBlockX(cell, (Integer) args.get(2)));
        args.set(3, WorldgenCoordinates.toGlobalBlockZ(cell, (Integer) args.get(3)));
    }

    @Inject(method = "create", at = @At("RETURN"))
    private static void largerworld$rememberSamplerCell(
            net.minecraft.world.chunk.Chunk chunk,
            NoiseConfig noiseConfig,
            net.minecraft.world.gen.densityfunction.DensityFunctionTypes.Beardifying beardifying,
            net.minecraft.world.gen.chunk.ChunkGeneratorSettings settings,
            net.minecraft.world.gen.chunk.AquiferSampler.FluidLevelSampler fluidLevelSampler,
            net.minecraft.world.gen.chunk.Blender blender,
            CallbackInfoReturnable<ChunkNoiseSampler> cir) {
        ChunkNoiseSampler sampler = cir.getReturnValue();
        CellPos cell = WorldgenCoordinates.cell(noiseConfig);
        WorldgenCoordinates.register(sampler, cell);
        WorldgenCoordinates.register(sampler.getAquiferSampler(), cell);
    }
}
