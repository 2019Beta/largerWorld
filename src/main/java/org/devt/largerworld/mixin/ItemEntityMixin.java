package org.devt.largerworld.mixin;

import net.minecraft.entity.ItemEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import org.devt.largerworld.world.CellBoundaryAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Extends vanilla item merging to items physically adjacent across a cell seam. */
@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin {
    @Inject(method = "tryMerge()V", at = @At("RETURN"))
    private void largerworld$mergeAcrossCell(CallbackInfo ci) {
        ItemEntity self = (ItemEntity) (Object) this;
        ItemEntityAccessor selfAccess = (ItemEntityAccessor) this;
        if (self.isRemoved() || !selfAccess.largerworld$canMerge()
                || !(self.getEntityWorld() instanceof ServerWorld source)) {
            return;
        }

        Box searchBox = self.getBoundingBox().expand(0.5, 0.0, 0.5);
        for (CellBoundaryAccess.ProjectedWorld projected
                : CellBoundaryAccess.loadedWorldsOverlapping(source, searchBox)) {
            for (ItemEntity other : projected.world().getEntitiesByClass(
                    ItemEntity.class,
                    projected.localBox(),
                    item -> !item.isRemoved())) {
                if (((ItemEntityAccessor) other).largerworld$canMerge()) {
                    selfAccess.largerworld$tryMerge(other);
                    if (self.isRemoved()) {
                        return;
                    }
                }
            }
        }
    }
}
