package org.devt.largerworld.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LazyEntityReference;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import net.minecraft.world.entity.UniquelyIdentifiable;
import org.devt.largerworld.world.CellBoundaryAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keeps UUID-backed entity relationships alive across a loaded cell seam. */
@Mixin(LazyEntityReference.class)
public abstract class LazyEntityReferenceMixin {
    @Inject(method = "getEntityByClass", at = @At("RETURN"), cancellable = true)
    private void largerworld$resolveAcrossCell(
            World world,
            Class<? extends UniquelyIdentifiable> entityClass,
            CallbackInfoReturnable<UniquelyIdentifiable> cir) {
        if (cir.getReturnValue() != null || !(world instanceof ServerWorld serverWorld)) {
            return;
        }
        UUIDAccessor reference = new UUIDAccessor((LazyEntityReference<?>) (Object) this);
        Entity entity = CellBoundaryAccess.findLoadedEntity(serverWorld, reference.uuid()).orElse(null);
        if (entity != null && entityClass.isInstance(entity)) {
            cir.setReturnValue(entity);
        }
    }

    private record UUIDAccessor(LazyEntityReference<?> reference) {
        private java.util.UUID uuid() {
            return reference.getUuid();
        }
    }
}
