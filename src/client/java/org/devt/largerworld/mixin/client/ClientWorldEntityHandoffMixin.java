package org.devt.largerworld.mixin.client;

import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import org.devt.largerworld.Largerworld;
import org.devt.largerworld.client.network.ClientEntityHandoff;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prevents the source tracker from deleting an entity that is moving to another cell. */
@Mixin(ClientWorld.class)
public abstract class ClientWorldEntityHandoffMixin {
    @Inject(method = "removeEntity", at = @At("HEAD"), cancellable = true)
    private void largerworld$keepEntityDuringHandoff(
            int entityId, Entity.RemovalReason reason, CallbackInfo ci) {
        ClientWorld world = (ClientWorld) (Object) this;
        if (ClientEntityHandoff.shouldKeep(world.getEntityById(entityId))) {
            Largerworld.LOGGER.info(
                    "[cell-transition] CLIENT_RETAIN_REMOVE entityId={} reason={}",
                    entityId, reason);
            ci.cancel();
        }
    }
}
