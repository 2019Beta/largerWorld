package org.devt.largerworld.mixin;

import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps aquifer control points lossless outside BlockPos' packed X/Z range.
 *
 * <p>Vanilla caches each randomly placed aquifer point in a packed BlockPos
 * long. That format only retains 26 horizontal bits, so a folded worldgen
 * coordinate such as -2,094,967,296 is truncated to an unrelated position.
 * The subsequent distance calculation then overflows and selects arbitrary
 * fluid levels. Keep the points in a sampler-local table instead; all density
 * functions and random splitters continue to receive the original global int
 * coordinates.</p>
 */
@Mixin(targets = "net.minecraft.world.gen.chunk.AquiferSampler$Impl")
public abstract class AquiferSamplerMixin {
    @Unique
    private List<BlockPos> largerworld$fullAquiferPositions;

    @Redirect(
            method = "apply",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/util/math/BlockPos;asLong(III)J"))
    private long largerworld$storeFullAquiferPosition(int x, int y, int z) {
        if (largerworld$fullAquiferPositions == null) {
            largerworld$fullAquiferPositions = new ArrayList<>();
        }

        long handle = Long.MIN_VALUE + largerworld$fullAquiferPositions.size();
        largerworld$fullAquiferPositions.add(new BlockPos(x, y, z));
        return handle;
    }

    @Redirect(
            method = {"apply", "getWaterLevel"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/util/math/BlockPos;unpackLongX(J)I"))
    private int largerworld$readFullAquiferX(long handle) {
        BlockPos position = largerworld$getFullAquiferPosition(handle);
        return position == null ? BlockPos.unpackLongX(handle) : position.getX();
    }

    @Redirect(
            method = {"apply", "getWaterLevel"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/util/math/BlockPos;unpackLongY(J)I"))
    private int largerworld$readFullAquiferY(long handle) {
        BlockPos position = largerworld$getFullAquiferPosition(handle);
        return position == null ? BlockPos.unpackLongY(handle) : position.getY();
    }

    @Redirect(
            method = {"apply", "getWaterLevel"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/util/math/BlockPos;unpackLongZ(J)I"))
    private int largerworld$readFullAquiferZ(long handle) {
        BlockPos position = largerworld$getFullAquiferPosition(handle);
        return position == null ? BlockPos.unpackLongZ(handle) : position.getZ();
    }

    @Unique
    private BlockPos largerworld$getFullAquiferPosition(long handle) {
        if (largerworld$fullAquiferPositions == null) {
            return null;
        }

        long index = handle - Long.MIN_VALUE;
        return index >= 0 && index < largerworld$fullAquiferPositions.size()
                ? largerworld$fullAquiferPositions.get((int) index)
                : null;
    }
}
