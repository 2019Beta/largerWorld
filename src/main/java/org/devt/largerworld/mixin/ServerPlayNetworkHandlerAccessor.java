package org.devt.largerworld.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerPlayNetworkHandler.class)
public interface ServerPlayNetworkHandlerAccessor {
    @Accessor("topmostRiddenEntity")
    void largerworld$setTopmostRiddenEntity(Entity entity);

    @Accessor("lastTickRiddenX")
    void largerworld$setLastTickRiddenX(double value);

    @Accessor("lastTickRiddenY")
    void largerworld$setLastTickRiddenY(double value);

    @Accessor("lastTickRiddenZ")
    void largerworld$setLastTickRiddenZ(double value);

    @Accessor("updatedRiddenX")
    void largerworld$setUpdatedRiddenX(double value);

    @Accessor("updatedRiddenY")
    void largerworld$setUpdatedRiddenY(double value);

    @Accessor("updatedRiddenZ")
    void largerworld$setUpdatedRiddenZ(double value);

    @Accessor("vehicleFloating")
    void largerworld$setVehicleFloating(boolean value);

    @Accessor("vehicleFloatingTicks")
    void largerworld$setVehicleFloatingTicks(int value);

}
