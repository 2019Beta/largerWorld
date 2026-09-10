package org.devt.largerworld.mixin;

import net.minecraft.world.gen.densityfunction.DensityFunction;
import org.devt.largerworld.world.WorldgenCoordinates;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(targets = "net.minecraft.world.gen.densityfunction.DensityFunctionTypes$ShiftedNoise")
public abstract class DensityShiftedNoiseMixin {
    @Shadow public abstract double xzScale();

    @Redirect(method = "sample", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/gen/densityfunction/DensityFunction$Noise;sample(DDD)D"))
    private double largerworld$continuousNoise(DensityFunction.Noise noise,
            double x, double y, double z, DensityFunction.NoisePos pos) {
        return WorldgenCoordinates.sampleNoise(noise, pos, xzScale(), x, y, z,
                WorldgenCoordinates.NoiseAxes.XZ);
    }
}
