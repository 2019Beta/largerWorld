package org.devt.largerworld.mixin;

import net.minecraft.entity.ai.goal.TrackTargetGoal;
import net.minecraft.entity.mob.MobEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Accesses state declared by TrackTargetGoal for subclass behavior mixins. */
@Mixin(TrackTargetGoal.class)
public interface TrackTargetGoalAccessor {
    @Accessor("mob")
    MobEntity largerworld$getMob();
}
