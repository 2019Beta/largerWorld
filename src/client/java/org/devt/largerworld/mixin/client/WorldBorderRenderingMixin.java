package org.devt.largerworld.mixin.client;

import net.minecraft.client.render.WorldBorderRendering;
import net.minecraft.client.render.state.WorldBorderRenderState;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Suppresses the world-border wall even when a server sends border updates. */
@Mixin(WorldBorderRendering.class)
public abstract class WorldBorderRenderingMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void largerworld$hideBorder(
            WorldBorderRenderState state,
            Vec3d cameraPos,
            double viewDistance,
            double farPlaneDistance,
            CallbackInfo ci) {
        ci.cancel();
    }
}
