package org.devt.largerworld.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.entity.TrackedPosition;
import net.minecraft.server.network.EntityTrackerEntry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import org.devt.largerworld.server.MinecartTrackerHandoff;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityTrackerEntry.class)
public abstract class EntityTrackerEntryMixin implements MinecartTrackerHandoff.Entry {
    @Shadow @Final private ServerWorld world;
    @Shadow @Final private Entity entity;
    @Shadow @Final private TrackedPosition trackedPos;
    @Shadow private Vec3d velocity;
    @Shadow private int trackingTick;
    @Shadow private int updatesWithoutVehicle;
    @Shadow private byte lastYaw;
    @Shadow private byte lastPitch;
    @Shadow private byte lastHeadYaw;
    @Shadow private boolean hadVehicle;
    @Shadow private boolean lastOnGround;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void largerworld$restoreMinecartTimeline(CallbackInfo ci) {
        MinecartTrackerHandoff.restore(this, entity, world);
    }

    @Override
    public MinecartTrackerHandoff.State largerworld$captureMinecartTracking() {
        return new MinecartTrackerHandoff.State(
                trackedPos.getPos(), velocity, trackingTick, updatesWithoutVehicle,
                lastYaw, lastPitch, lastHeadYaw, hadVehicle, lastOnGround);
    }

    @Override
    public void largerworld$restoreMinecartTracking(MinecartTrackerHandoff.State state, Vec3d offset) {
        // Spawn packets read this baseline too. Keep it at the last SENT
        // position, translated into the destination cell, rather than skipping
        // to the entity's current position. The next relative packet then
        // covers the entire unsent movement on the original sending cadence.
        trackedPos.setPos(state.position().add(offset));
        velocity = state.velocity();
        trackingTick = state.trackingTick();
        updatesWithoutVehicle = state.updatesWithoutVehicle();
        lastYaw = state.yaw();
        lastPitch = state.pitch();
        lastHeadYaw = state.headYaw();
        hadVehicle = state.hadVehicle();
        lastOnGround = state.onGround();
    }
}
