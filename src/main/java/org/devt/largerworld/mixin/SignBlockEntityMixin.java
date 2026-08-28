package org.devt.largerworld.mixin;

import net.minecraft.block.entity.SignBlockEntity;
import org.devt.largerworld.server.CellInteractionRouting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

@Mixin(SignBlockEntity.class)
public abstract class SignBlockEntityMixin {
    @Inject(method = "isPlayerTooFarToEdit", at = @At("HEAD"), cancellable = true)
    private void largerworld$keepRemoteEditor(UUID editor, CallbackInfoReturnable<Boolean> cir) {
        if (CellInteractionRouting.isRemoteSignEditor(
                (SignBlockEntity) (Object) this, editor)) {
            cir.setReturnValue(false);
        }
    }
}
