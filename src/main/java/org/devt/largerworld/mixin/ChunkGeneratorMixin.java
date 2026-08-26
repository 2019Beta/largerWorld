package org.devt.largerworld.mixin;

import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.chunk.placement.StructurePlacementCalculator;
import org.devt.largerworld.coordinate.CellPos;
import org.devt.largerworld.world.WorldgenCoordinates;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Mixin(ChunkGenerator.class)
public abstract class ChunkGeneratorMixin {
    @ModifyArgs(
            method = "method_41041",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/gen/chunk/placement/StructurePlacement;shouldGenerate(Lnet/minecraft/world/gen/chunk/placement/StructurePlacementCalculator;II)Z"))
    private void largerworld$testStructurePlacementAtGlobalChunk(Args args) {
        StructurePlacementCalculator calculator = args.get(0);
        CellPos cell = WorldgenCoordinates.cell(calculator.getNoiseConfig());
        ChunkPos globalPos = WorldgenCoordinates.toGlobalChunk(
                cell, new ChunkPos((Integer) args.get(1), (Integer) args.get(2)));
        args.set(1, globalPos.x);
        args.set(2, globalPos.z);
    }

    @ModifyArgs(
            method = "method_41041",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/util/math/random/ChunkRandom;setCarverSeed(JII)V"))
    private void largerworld$seedStructureChoiceFromGlobalChunk(Args args) {
        CellPos cell = WorldgenCoordinates.cell((ChunkGenerator) (Object) this);
        ChunkPos globalPos = WorldgenCoordinates.toGlobalChunk(
                cell, new ChunkPos((Integer) args.get(1), (Integer) args.get(2)));
        args.set(1, globalPos.x);
        args.set(2, globalPos.z);
    }

    @ModifyArgs(
            method = "generateFeatures",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/util/math/random/ChunkRandom;setPopulationSeed(JII)J"))
    private void largerworld$seedDecorationFromGlobalBlock(Args args) {
        CellPos cell = WorldgenCoordinates.cell((ChunkGenerator) (Object) this);
        args.set(1, WorldgenCoordinates.toGlobalBlockX(cell, (Integer) args.get(1)));
        args.set(2, WorldgenCoordinates.toGlobalBlockZ(cell, (Integer) args.get(2)));
    }
}
