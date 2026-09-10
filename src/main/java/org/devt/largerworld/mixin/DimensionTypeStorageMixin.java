package org.devt.largerworld.mixin;

import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;
import net.minecraft.world.dimension.DimensionType;
import org.devt.largerworld.world.CellStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.file.Path;

@Mixin(DimensionType.class)
public abstract class DimensionTypeStorageMixin {
    @Inject(method = "getSaveDirectory", at = @At("HEAD"), cancellable = true)
    private static void largerworld$compactCellDirectory(
            RegistryKey<World> key, Path root, CallbackInfoReturnable<Path> cir) {
        Path directory = CellStorage.directory(key, root);
        if (directory != null) {
            cir.setReturnValue(directory);
        }
    }
}
