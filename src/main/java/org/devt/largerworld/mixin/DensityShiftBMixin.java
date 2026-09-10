package org.devt.largerworld.mixin;

import net.minecraft.world.gen.densityfunction.DensityFunction;
import org.devt.largerworld.world.WorldgenCoordinates;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.minecraft.world.gen.densityfunction.DensityFunctionTypes$ShiftB")
public abstract class DensityShiftBMixin {
    @Shadow public abstract DensityFunction.Noise offsetNoise();

    @Inject(method = "sample(Lnet/minecraft/world/gen/densityfunction/DensityFunction$NoisePos;)D",
            at = @At("HEAD"), cancellable = true)
    private void largerworld$continuousShift(DensityFunction.NoisePos pos, CallbackInfoReturnable<Double> cir) {
        cir.setReturnValue(4 * WorldgenCoordinates.sampleNoise(offsetNoise(), pos, 0.25,
                pos.blockZ() * 0.25, pos.blockX() * 0.25, 0,
                WorldgenCoordinates.NoiseAxes.ZX_IN_XY));
    }
}
