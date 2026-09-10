package org.devt.largerworld.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.gen.densityfunction.DensityFunction;
import org.devt.largerworld.world.WorldgenCoordinates;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(targets = "net.minecraft.world.gen.densityfunction.DensityFunctionTypes$WeirdScaledSampler")
public abstract class DensityWeirdScaledMixin {
    @Redirect(method = "apply(Lnet/minecraft/world/gen/densityfunction/DensityFunction$NoisePos;D)D",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/gen/densityfunction/DensityFunction$Noise;sample(DDD)D"))
    private double largerworld$continuousCaveNoise(DensityFunction.Noise noise,
            double x, double y, double z, @Local(argsOnly = true) DensityFunction.NoisePos pos,
            @Local(ordinal = 1) double rarity) {
        return WorldgenCoordinates.sampleNoise(noise, pos, 1 / rarity, x, y, z,
                WorldgenCoordinates.NoiseAxes.XZ);
    }
}
