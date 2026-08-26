package org.devt.largerworld.mixin;

import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import org.devt.largerworld.world.CellWorldKey;
import org.devt.largerworld.world.CellWorldManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {
    @Inject(method = "getWorld", at = @At("RETURN"), cancellable = true)
    private void largerworld$loadPersistedCellWorld(
            RegistryKey<World> key, CallbackInfoReturnable<ServerWorld> cir) {
        if (cir.getReturnValue() == null && CellWorldKey.parse(key).isPresent()) {
            cir.setReturnValue(CellWorldManager.getOrCreate((MinecraftServer) (Object) this, key));
        }
    }
}
