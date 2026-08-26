package org.devt.largerworld.mixin;

import net.minecraft.world.TeleportTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(TeleportTarget.class)
public interface TeleportTargetAccessor {
    @Accessor("asPassenger")
    boolean largerworld$isAsPassenger();
}
