package org.devt.largerworld.mixin;

import net.minecraft.server.world.ServerWorld;
import org.devt.largerworld.world.CellWorldKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerWorld.class)
public abstract class ServerWorldMixin {
    @Inject(method = "getSeed", at = @At("RETURN"), cancellable = true)
    private void largerworld$deriveCellSeed(CallbackInfoReturnable<Long> cir) {
        ServerWorld self = (ServerWorld) (Object) this;
        CellWorldKey.parse(self.getRegistryKey()).ifPresent(parsed -> {
            long seed = cir.getReturnValue();
            seed ^= mix64(parsed.cell().x());
            seed ^= Long.rotateLeft(mix64(parsed.cell().z()), 31);
            cir.setReturnValue(mix64(seed));
        });
    }

    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }
}
