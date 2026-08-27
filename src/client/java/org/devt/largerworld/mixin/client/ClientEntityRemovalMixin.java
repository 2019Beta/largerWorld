package org.devt.largerworld.mixin.client;

import net.minecraft.entity.Entity;
import org.devt.largerworld.Largerworld;
import org.devt.largerworld.client.network.ClientEntityHandoff;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Covers direct entity removals that bypass ClientWorld.removeEntity. */
@Mixin(Entity.class)
public abstract class ClientEntityRemovalMixin {
    @Inject(method = "setRemoved", at = @At("HEAD"), cancellable = true)
    private void largerworld$keepEntityDuringHandoff(
            Entity.RemovalReason reason, CallbackInfo ci) {
        Entity entity = (Entity) (Object) this;
        if (ClientEntityHandoff.shouldKeep(entity)) {
            Largerworld.LOGGER.info(
                    "[cell-transition] CLIENT_RETAIN_DIRECT_REMOVE entityId={} reason={}",
                    entity.getId(), reason);
            ci.cancel();
        }
    }
}
