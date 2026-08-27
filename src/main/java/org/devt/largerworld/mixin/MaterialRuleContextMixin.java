package org.devt.largerworld.mixin;

import net.minecraft.world.gen.chunk.ChunkNoiseSampler;
import org.devt.largerworld.coordinate.CellPos;
import org.devt.largerworld.world.WorldgenCoordinates;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Keeps surface-rule height lookups in the sampler's global coordinate space. */
@Mixin(targets = "net.minecraft.world.gen.surfacebuilder.MaterialRules$MaterialRuleContext")
public abstract class MaterialRuleContextMixin {
    @Redirect(
            method = "estimateSurfaceHeight",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/gen/chunk/ChunkNoiseSampler;estimateSurfaceHeight(II)I"))
    private int largerworld$estimateGlobalSurfaceHeight(
            ChunkNoiseSampler sampler, int localX, int localZ) {
        CellPos cell = WorldgenCoordinates.cell(sampler);
        return sampler.estimateSurfaceHeight(
                WorldgenCoordinates.toGlobalBlockX(cell, localX),
                WorldgenCoordinates.toGlobalBlockZ(cell, localZ));
    }
}
