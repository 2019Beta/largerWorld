package org.devt.largerworld.mixin;

import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import org.devt.largerworld.world.WorldgenCoordinates;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Chunk.class)
public abstract class ChunkMixin {
    @Shadow @Final protected ChunkPos pos;

    @Inject(method = "getPos", at = @At("HEAD"), cancellable = true)
    private void largerworld$exposeGlobalWorldgenPosition(CallbackInfoReturnable<ChunkPos> cir) {
        ChunkPos shifted = WorldgenCoordinates.shiftedPos((Chunk) (Object) this, pos);
        if (shifted != pos) {
            cir.setReturnValue(shifted);
        }
    }
}
