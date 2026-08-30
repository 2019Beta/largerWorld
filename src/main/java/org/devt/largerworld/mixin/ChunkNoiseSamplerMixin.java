package org.devt.largerworld.mixin;

import net.minecraft.world.gen.chunk.ChunkNoiseSampler;
import net.minecraft.world.gen.noise.NoiseConfig;
import net.minecraft.world.gen.densityfunction.DensityFunction;
import org.devt.largerworld.coordinate.CellPos;
import org.devt.largerworld.world.WorldgenCoordinates;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
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

    @Inject(method = "<init>", at = @At("RETURN"))
    private void largerworld$rememberSamplerCell(
            int horizontalCellCount,
            NoiseConfig noiseConfig,
            int startBlockX,
            int startBlockZ,
            net.minecraft.world.gen.chunk.GenerationShapeConfig shapeConfig,
            net.minecraft.world.gen.densityfunction.DensityFunctionTypes.Beardifying beardifying,
            net.minecraft.world.gen.chunk.ChunkGeneratorSettings settings,
            net.minecraft.world.gen.chunk.AquiferSampler.FluidLevelSampler fluidLevelSampler,
            net.minecraft.world.gen.chunk.Blender blender,
            CallbackInfo ci) {
        ChunkNoiseSampler sampler = (ChunkNoiseSampler) (Object) this;
        WorldgenCoordinates.register(sampler, noiseConfig);
        WorldgenCoordinates.register(sampler.getAquiferSampler(), noiseConfig);
    }

    @Redirect(
            method = "method_40530",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/gen/densityfunction/DensityFunction;sample(Lnet/minecraft/world/gen/densityfunction/DensityFunction$NoisePos;)D"))
    private double largerworld$addArbitraryPrecisionDensity(
            DensityFunction density, DensityFunction.NoisePos pos) {
        ChunkNoiseSampler sampler = (ChunkNoiseSampler) (Object) this;
        double base = density.sample(pos);
        return base + WorldgenCoordinates.densityOffset(sampler, pos.blockX(), pos.blockZ());
    }
}
