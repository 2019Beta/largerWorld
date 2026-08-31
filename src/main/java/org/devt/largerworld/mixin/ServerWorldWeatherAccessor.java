package org.devt.largerworld.mixin;

import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Recomputes transient rain/thunder gradients after loading cell properties. */
@Mixin(World.class)
public interface ServerWorldWeatherAccessor {
    @Invoker("initWeatherGradients")
    void largerworld$initWeatherGradients();
}
