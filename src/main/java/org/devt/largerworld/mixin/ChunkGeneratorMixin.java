package org.devt.largerworld.mixin;

import com.mojang.datafixers.util.Pair;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.entry.RegistryEntryList;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.chunk.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.gen.chunk.placement.StructurePlacementCalculator;
import net.minecraft.world.gen.structure.Structure;
import org.devt.largerworld.coordinate.CellPos;
import org.devt.largerworld.world.WorldgenCoordinates;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Mixin(ChunkGenerator.class)
public abstract class ChunkGeneratorMixin {
    @Unique
    private static final ThreadLocal<CellPos> LARGERWORLD_LOCATE_CELL = new ThreadLocal<>();

    @Inject(method = "locateStructure", at = @At("HEAD"))
    private void largerworld$enterStructureLocate(
            ServerWorld world,
            RegistryEntryList<Structure> structures,
            BlockPos center,
            int radius,
            boolean skipReferencedStructures,
            CallbackInfoReturnable<Pair<BlockPos, RegistryEntry<Structure>>> cir) {
        LARGERWORLD_LOCATE_CELL.set(WorldgenCoordinates.cell((ChunkGenerator) (Object) this));
    }

    @Inject(method = "locateStructure", at = @At("RETURN"))
    private void largerworld$leaveStructureLocate(
            ServerWorld world,
            RegistryEntryList<Structure> structures,
            BlockPos center,
            int radius,
            boolean skipReferencedStructures,
            CallbackInfoReturnable<Pair<BlockPos, RegistryEntry<Structure>>> cir) {
        LARGERWORLD_LOCATE_CELL.remove();
    }

    /**
     * Vanilla derives random-spread candidates from the local search center.
     * Structure placement, however, is seeded in our folded global coordinate
     * space. At a distant cell that mismatch makes every candidate fail and
     * /locate walks the entire radius while synchronously loading chunks.
     *
     * Generate the candidate in global space, then map it back before vanilla
     * asks the backing cell world to inspect/load the chunk.
     */
    @Redirect(
            method = "locateRandomSpreadStructure",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/gen/chunk/placement/RandomSpreadStructurePlacement;getStartChunk(JII)Lnet/minecraft/util/math/ChunkPos;"))
    private static ChunkPos largerworld$getLocateCandidateInCell(
            RandomSpreadStructurePlacement placement, long seed, int localX, int localZ) {
        CellPos cell = LARGERWORLD_LOCATE_CELL.get();
        if (cell == null || cell.equals(CellPos.ZERO)) {
            return placement.getStartChunk(seed, localX, localZ);
        }

        ChunkPos globalSearchPos = WorldgenCoordinates.toGlobalChunk(cell, new ChunkPos(localX, localZ));
        ChunkPos globalCandidate = placement.getStartChunk(seed, globalSearchPos.x, globalSearchPos.z);
        return new ChunkPos(
                WorldgenCoordinates.toLocalChunkX(cell, globalCandidate.x),
                WorldgenCoordinates.toLocalChunkZ(cell, globalCandidate.z));
    }

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
