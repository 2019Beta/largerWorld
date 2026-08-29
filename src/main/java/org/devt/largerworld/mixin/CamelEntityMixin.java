package org.devt.largerworld.mixin;

import net.minecraft.entity.passive.CamelEntity;
import org.devt.largerworld.Largerworld;
import org.devt.largerworld.server.CamelHandoffGrace;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prevents every sit request, regardless of its AI call path, during handoff. */
@Mixin(CamelEntity.class)
public abstract class CamelEntityMixin {
    @Inject(method = "startSitting", at = @At("HEAD"), cancellable = true)
    private void largerworld$preserveStandingPose(CallbackInfo ci) {
        CamelEntity camel = (CamelEntity) (Object) this;
        if (!CamelHandoffGrace.shouldSuppressPoseToggle(camel)) {
            return;
        }
        Largerworld.LOGGER.info(
                "[cross-camel] phase=SUPPRESS_START_SITTING id={} worldTime={} lastPoseTick={}",
                camel.getId(), camel.getEntityWorld().getTime(),
                camel.getDataTracker().get(CamelEntity.LAST_POSE_TICK));
        ci.cancel();
    }
}
