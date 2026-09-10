package org.devt.largerworld.mixin.client;

import net.minecraft.client.network.ClientPlayerEntity;
import org.devt.largerworld.client.network.ClientPlayerOriginState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(ClientPlayerEntity.class)
public abstract class ClientPlayerOriginMixin implements ClientPlayerOriginState {
    @Shadow private double lastXClient;
    @Shadow private double lastZClient;

    @Override
    public void largerworld$shiftLastSentPosition(double x, double z) {
        lastXClient += x;
        lastZClient += z;
    }
}
