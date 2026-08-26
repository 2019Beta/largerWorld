package org.devt.largerworld.mixin;

import net.minecraft.block.BlockState;
import net.minecraft.world.gen.carver.Carver;
import net.minecraft.world.gen.chunk.AquiferSampler;
import net.minecraft.world.gen.densityfunction.DensityFunction;
import org.devt.largerworld.coordinate.CellPos;
import org.devt.largerworld.world.WorldgenCoordinates;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Carver.class)
public abstract class CarverMixin {
    @Redirect(
            method = "getState",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/gen/chunk/AquiferSampler;apply(Lnet/minecraft/world/gen/densityfunction/DensityFunction$NoisePos;D)Lnet/minecraft/block/BlockState;"))
    private BlockState largerworld$sampleAquiferAtGlobalPosition(
            AquiferSampler aquifer, DensityFunction.NoisePos localPos, double density) {
        CellPos cell = WorldgenCoordinates.cell(aquifer);
        if (cell.equals(CellPos.ZERO)) {
            return aquifer.apply(localPos, density);
        }

        DensityFunction.NoisePos globalPos = new DensityFunction.UnblendedNoisePos(
                WorldgenCoordinates.toGlobalBlockX(cell, localPos.blockX()),
                localPos.blockY(),
                WorldgenCoordinates.toGlobalBlockZ(cell, localPos.blockZ()));
        return aquifer.apply(globalPos, density);
    }
}
