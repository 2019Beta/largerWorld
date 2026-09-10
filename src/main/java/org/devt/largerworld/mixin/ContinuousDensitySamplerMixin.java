package org.devt.largerworld.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.gen.densityfunction.DensityFunction;
import org.devt.largerworld.world.ContinuousDensitySampler;
import org.devt.largerworld.world.WorldgenCoordinates;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(targets = {"net.minecraft.util.math.noise.InterpolatedNoiseSampler",
        "net.minecraft.world.gen.densityfunction.DensityFunctionTypes$EndIslands"})
public abstract class ContinuousDensitySamplerMixin implements ContinuousDensitySampler {
    // EndIslands also has a private static sample(SimplexNoiseSampler, int, int)
    // helper.  Naming the DensityFunction overload prevents WrapMethod from
    // resolving that helper and reporting an instance/static mismatch.
    @WrapMethod(method = "sample(Lnet/minecraft/world/gen/densityfunction/DensityFunction$NoisePos;)D")
    private double largerworld$continuousNativeDensity(
            DensityFunction.NoisePos pos, Operation<Double> original) {
        return WorldgenCoordinates.sampleDensity((DensityFunction) (Object) this, pos,
                bounded -> original.call(bounded));
    }
}
