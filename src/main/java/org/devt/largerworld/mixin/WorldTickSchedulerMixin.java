package org.devt.largerworld.mixin;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.tick.OrderedTick;
import net.minecraft.world.tick.WorldTickScheduler;
import org.devt.largerworld.server.CellTickSchedulerRouting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WorldTickScheduler.class)
public abstract class WorldTickSchedulerMixin {
    @Inject(method = "scheduleTick", at = @At("HEAD"), cancellable = true)
    private void largerworld$scheduleAcrossCell(OrderedTick<?> tick, CallbackInfo ci) {
        if (CellTickSchedulerRouting.routeSchedule(
                (WorldTickScheduler<?>) (Object) this, tick)) {
            ci.cancel();
        }
    }

    @Inject(method = "isQueued", at = @At("HEAD"), cancellable = true)
    private void largerworld$isQueuedAcrossCell(
            BlockPos pos, Object type, CallbackInfoReturnable<Boolean> cir) {
        Boolean result = CellTickSchedulerRouting.routeIsQueued(
                (WorldTickScheduler<?>) (Object) this, pos, type, false);
        if (result != null) {
            cir.setReturnValue(result);
        }
    }

    @Inject(method = "isTicking", at = @At("HEAD"), cancellable = true)
    private void largerworld$isTickingAcrossCell(
            BlockPos pos, Object type, CallbackInfoReturnable<Boolean> cir) {
        Boolean result = CellTickSchedulerRouting.routeIsQueued(
                (WorldTickScheduler<?>) (Object) this, pos, type, true);
        if (result != null) {
            cir.setReturnValue(result);
        }
    }
}
