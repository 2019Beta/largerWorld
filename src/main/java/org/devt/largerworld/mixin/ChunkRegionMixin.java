package org.devt.largerworld.mixin;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.collection.BoundedRegionArray;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.ChunkRegion;
import net.minecraft.world.chunk.AbstractChunkHolder;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkGenerationStep;
import org.devt.largerworld.coordinate.CellPos;
import org.devt.largerworld.world.CellWorldKey;
import org.devt.largerworld.world.WorldgenCoordinates;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkRegion.class)
public abstract class ChunkRegionMixin {
    @Unique
    private static final long LARGERWORLD_REGION_RANDOM_DOMAIN = 0x524547494f4e5f31L;
    @Unique
    private static final Identifier LARGERWORLD_WORLDGEN_RANDOM_ID =
            Identifier.ofVanilla("worldgen_region_random");

    @Shadow @Final private Chunk centerPos;
    @Shadow @Final private ServerWorld world;
    @Shadow @Final @Mutable private Random random;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void largerworld$seedRegionRandomFromGlobalPosition(
            ServerWorld world,
            BoundedRegionArray<AbstractChunkHolder> chunks,
            ChunkGenerationStep generationStep,
            Chunk centerPos,
            CallbackInfo ci) {
        CellPos cell = CellWorldKey.cell(world.getRegistryKey());
        if (cell.equals(CellPos.ZERO)) {
            return;
        }

        ChunkPos globalPos = WorldgenCoordinates.toRandomChunk(
                cell, centerPos.getPos(), LARGERWORLD_REGION_RANDOM_DOMAIN);
        random = world.getChunkManager()
                .getNoiseConfig()
                .getOrCreateRandomDeriver(LARGERWORLD_WORLDGEN_RANDOM_ID)
                .split(globalPos.getStartPos());
    }

}
