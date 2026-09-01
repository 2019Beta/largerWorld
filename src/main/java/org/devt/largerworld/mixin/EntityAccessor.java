package org.devt.largerworld.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Entity.class)
public interface EntityAccessor {
    @Invoker("unsetRemoved")
    void largerworld$unsetRemoved();

    @Invoker("setWorld")
    void largerworld$setWorld(World world);

    @Accessor("lastPos")
    void largerworld$setLastPos(Vec3d position);
}
